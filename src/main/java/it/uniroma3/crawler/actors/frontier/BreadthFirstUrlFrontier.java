package it.uniroma3.crawler.actors.frontier;

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
import it.uniroma3.crawler.model.CrawlURL;
import scala.concurrent.duration.Duration;

public class BreadthFirstUrlFrontier extends UntypedActor implements UrlFrontier  {
	private Queue<CrawlURL> urlsQueue;
	private final ActorSystem system;

	public BreadthFirstUrlFrontier() {
		this.urlsQueue = new PriorityQueue<>(
				(CrawlURL c1, CrawlURL c2) -> c1.compareTo(c2));
		this.system = getContext().system();
	}

	@Override
	public CrawlURL next() {
		return urlsQueue.poll();
	}

	@Override
	public void scheduleUrl(CrawlURL crawUrl) {
		urlsQueue.add(crawUrl);
	}

	@Override
	public boolean isEmpty() {
		return urlsQueue.isEmpty();
	}

	@Override
	public void onReceive(Object message) throws Throwable {
		if (message.equals("next")) {
			if (!isEmpty()) {
			// handle request from fetcher for next url to be processed
			long wait = urlsQueue.peek().getPageClass().getWaitTime();
			system.scheduler().scheduleOnce(Duration
					.create(wait, TimeUnit.MILLISECONDS),
					  getSender(), next(), system.dispatcher(), null);
			}
			else { // frontier is empty, we must wait (?)
				system.scheduler().scheduleOnce(Duration
						.create(2000, TimeUnit.MILLISECONDS),
						  getSelf(), "next", system.dispatcher(), null);
			}
		}
		
		else if (message instanceof CrawlURL) {
			// store the received url
			CrawlURL cUrl = (CrawlURL) message;
			scheduleUrl(cUrl);
		}
		
		else unhandled(message);
	}

}
