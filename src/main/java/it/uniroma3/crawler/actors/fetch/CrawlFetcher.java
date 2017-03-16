package it.uniroma3.crawler.actors.fetch;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.japi.Creator;
import it.uniroma3.crawler.CrawlController;
import it.uniroma3.crawler.model.CrawlURL;
import it.uniroma3.crawler.util.HtmlUtils;
import scala.concurrent.duration.Duration;

public class CrawlFetcher extends UntypedActor {
	private final static String NEXT = "next", START = "Start";
	private final static int MAX_FAILURES = 3;
	private final static int TIME_TO_WAIT = 1000*3600*2;
	private int failures;
	private WebClient webClient;
	private Logger log;
	private List<ActorRef> extractors;
	
	  public static Props props(final List<ActorRef> extractors) {
		    return Props.create(new Creator<CrawlFetcher>() {
		      private static final long serialVersionUID = 1L;
		 
		      @Override
		      public CrawlFetcher create() throws Exception {
		        return new CrawlFetcher(extractors);
		      }
		    });
		  }
	
	public CrawlFetcher(List<ActorRef> extractors) {
		this.webClient = HtmlUtils.makeWebClient();
		this.log = Logger.getLogger(CrawlFetcher.class.getName());
		this.extractors = extractors;
		this.failures = 0;
	}

	@Override
	public void onReceive(Object message) throws Throwable {
		if (message instanceof CrawlURL) {
			CrawlURL cUrl = (CrawlURL) message;
			String url = cUrl.getStringUrl();
			try {
				HtmlPage body = HtmlUtils.getPage(url, webClient);
				log.info("Page reached = "+url+" - "+body.getTitleText());
				if (isValidResponse(body)) {
					cUrl.setPageContent(body);
					failures = 0; // everything went ok
					ActorRef extractor = getExtractorRef(cUrl.getPageClass().getName());
					// send cUrl to extractor for further processing
					extractor.forward(cUrl, getContext());
					
					// request next cUrl to Frontier
					getSender().tell(NEXT, getSelf());
				}
				else {
					// send crawl url back to the frontier
					getSender().tell(cUrl, getSelf());
					waitAndRequestNext(TIME_TO_WAIT); // 2 hours
				}

			} catch (Exception e) {
				log.warning("HTTP REQUEST: FAILED");
				failures++;
				if (failures <= MAX_FAILURES) {
					log.warning("HTTP REQUEST: TRY AGAIN...");
					getSelf().forward(cUrl, context());
				}
				else { // try later..
					failures = 0;
					waitAndRequestNext(TIME_TO_WAIT); // 2 hours
				}
			}
		}
		else if (message.equals(START)) {
			// first request
			CrawlController.getInstance().getFrontier().tell(NEXT, getSelf());
		}
		else unhandled(message);

	}
	
	private ActorRef getExtractorRef(String name) {
		return extractors.stream()
				.filter(e -> e.path().name().equals(name))
				.findAny().orElse(null);
	}
	
	private void waitAndRequestNext(int time) {
		// wait time befor requesting
		log.warning("HTTP REQUEST: WAIT FOR "+
				TimeUnit.MILLISECONDS.toMinutes(time)+" minutes");
		context().system().scheduler().scheduleOnce(Duration
				.create(time, TimeUnit.MILLISECONDS),
				getSender(), NEXT, context().system().dispatcher(), getSelf());

	}
	
	private boolean isValidResponse(HtmlPage page) {
		return page.getWebResponse().getContentAsString().contains("html");
	}
	
	@Override
	public void postStop() {
		this.webClient.close();
	}
}
