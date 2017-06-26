package it.uniroma3.crawler.modeler;

import static it.uniroma3.crawler.util.HtmlUtils.*;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static it.uniroma3.crawler.util.XPathUtils.getAbsoluteURLs;
import static it.uniroma3.crawler.util.Commands.STOP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import akka.actor.AbstractLoggingActor;
import akka.util.ByteString;
import it.uniroma3.crawler.model.PageClass;
import it.uniroma3.crawler.modeler.model.ModelPageClass;
import it.uniroma3.crawler.modeler.evaluator.ModelerEvaluator;
import it.uniroma3.crawler.modeler.model.LinkCollection;
import it.uniroma3.crawler.modeler.model.Page;
import it.uniroma3.crawler.modeler.model.WebsiteModel;
import it.uniroma3.crawler.modeler.model.XPath;
import it.uniroma3.crawler.settings.CrawlerSettings.SeedConfig;
import it.uniroma3.crawler.util.FileUtils;
import it.uniroma3.crawler.util.HtmlUtils;
import scala.concurrent.duration.Duration;

public class DynamicModeler extends AbstractLoggingActor {
	
	private SeedConfig conf; // website configuration

	/**
	 * Model of this website
	 */
	private final WebsiteModel model = new WebsiteModel();
	
	private WebClient client;
	
	/**
	 * the queue of discovered Link Collections
	 */
	private Queue<LinkCollection> queue =
			new PriorityQueue<>((l1,l2) -> l1.densestFirst(l2,model));
	
	/**
	 * map of visited pages
	 */
	private Map<String,Page> visitedURLs = new HashMap<>();
			
	/**
	 * current id of last created {@link ModelPageClass}
	 */
	private int id;
	
	/**
	 * current list of new pages from latest outgoing links
	 */
	private List<Page> newPages = new ArrayList<>();
	
	/**
	 * last polled LinkCollection
	 */
	private LinkCollection collection;
	
	/**
	 * current queue of outgoing links being fetched
	 */
	private Queue<String> links;
	
	/**
	 * current list of candidate clusters
	 */
	private List<ModelPageClass> candidates;
	
	@Override
	public Receive createReceive() {
		return receiveBuilder()
		.match(SeedConfig.class, this::start)
		.matchEquals("poll", msg -> poll())
		.matchEquals("getLinks", msg -> getLinks())
		.matchEquals("fetch", msg -> fetch())
		.matchEquals("cluster", msg -> cluster())
		.matchEquals("inspect", msg -> inspect())
		.matchEquals("refine", msg -> changeXPath())
		.matchEquals("update", msg -> update())
		.matchEquals("finalize", msg -> finalizeModel())
		.match(ByteString.class, golden -> 
			context().actorOf(ModelerEvaluator.props(golden), "evaluator")
			.tell(model, context().parent()))
		.build();
	}
	
	public void start(SeedConfig sc) {
		conf = sc;
		client = makeWebClient(sc.javascript);

		// Init. site
		try {
			HtmlPage html = getPage(conf.site, client);
			String baseURL = html.getBaseURL().toExternalForm();
			baseURL = baseURL.substring(0, baseURL.length()-1);
			if (!baseURL.equals(conf.site))
				conf.site = new String(baseURL);
			Page p = new Page(conf.site, html);
			visitedURLs.put(conf.site, p);
			newPages.add(p);
			model.addClass(new ModelPageClass(++id, Arrays.asList(p)));
			p.classified();
			queue.addAll(p.getLinkCollections());
		} catch (Exception e) {
			log().warning("Failed fetching Seed: "+conf.site+", "+e.getMessage());
		}
		self().tell("poll", self());
	}
	
	public void poll() {
		if (!queue.isEmpty()) {
			collection = queue.poll();
			getLinks();
		}
		else self().tell("finalize", self());
	}
	
	public void getLinks() {
		log().info("Parent Page: "+collection.getPage()+", "+collection.size()+" links");
		links = collection.getLinksToFetch(conf.site);
		
		if (newPages.stream().anyMatch(p -> !p.isLoaded())) // if some page was downloaded, wait
			context().system().scheduler().scheduleOnce(
					Duration.create(conf.wait, TimeUnit.MILLISECONDS), self(), 
					"fetch", context().dispatcher(), self());
		else self().tell("fetch", self());
		
		/* reset pages */
		newPages.clear();
	}
	
	public void fetch() {
		String msg = "fetch";
		
		if (!links.isEmpty()) {
			String url = links.poll();
			Page page = visitedURLs.get(url);
			if (page!=null) {
				page.setLoaded();
				newPages.add(page);
				log().info("Loaded: "+url);
			}
			else if (visitedURLs.size()<conf.modelPages) {
				try {
					HtmlPage html = getPage(url, client);
					page = new Page(url, html);
					visitedURLs.put(url, page);
					newPages.add(page);
					log().info("Fetched: "+url);
				}
				catch (Exception e) {
					log().warning("Failed fetching: "+url+", "+e.getMessage());
				}
			}
			else {
				/* end, reset queue */
				queue.clear();
				msg = "poll";
			}
		}
		else msg = (!newPages.isEmpty()) ? "cluster" : "poll";
		
		self().tell(msg, self());
	}
	
	/* 
	 * Candidate classes selection
	 * Collapse classes with similar structure
	 */
	public void cluster() {
		candidates = newPages.stream()
			.collect(groupingBy(Page::getDefaultSchema)).values().stream()
			.map(groupedPages -> new ModelPageClass((++id),groupedPages))
			.sorted((c1,c2) -> c2.size()-c1.size())
			.collect(toList());
		
		Set<ModelPageClass> deleted = new HashSet<>();
		for (int i = 0; i < candidates.size(); i++) {
			for (int j = candidates.size() - 1; j > i; j--) {
				ModelPageClass ci = candidates.get(i);
				ModelPageClass cj = candidates.get(j);
				if (!deleted.contains(ci) && !deleted.contains(cj)) {
					if (ci.distance(cj) < 0.2) {
						ci.collapse(cj);
						deleted.add(cj);
					}
				}
			}
		}
		candidates.removeAll(deleted);
		
		inspect();
	}
	
	/*
	 * Inspect the candidates and take actions on the base of:
	 * - Number of newPages fetched
	 * - Number of clusters created
	 */
	public void inspect() {		
		String msg = "update";
		
		if (newPages.size()==3 && candidates.size()==1)
			collection.setList();
		else if (newPages.size()==3 && candidates.size()==2) {
			if (!collection.isFinest()) {
				collection.setFiner(true);
				msg = "refine";
			}
			else collection.setList();
		}
		else if (candidates.size()>=3 && !collection.isMenu()) {
			collection.fetchAll();
			collection.setMenu();
			msg = "getLinks";
			log().info("MENU: FETCHING ALL URLS IN LINK COLLECTION...");
		}
		else if (newPages.size()==2 && candidates.size()==1)
			collection.setList();
		else if (newPages.size()==2 && candidates.size()==2)
			collection.setMenu();
		else if (newPages.size()==1) {
			if (!collection.isCoarsest() && !collection.isFiner())
				msg = "refine";
			else collection.setSingleton();
		}
		
		self().tell(msg, self());
	}
	
	public void update() {		
		/* skip pages already classified */
		candidates.removeIf(c -> {
			c.getPages().removeIf(Page::isClassified);
			return c.size()==0;
		});
		
		if (!candidates.isEmpty()) updateModel(candidates);
		setPageLinks(collection);
		
		newPages.stream()
				.filter(p -> !p.classified())
				.map(Page::getLinkCollections)
				.flatMap(Set::stream)
				.forEach(queue::add);
		
		self().tell("poll", self());
	}
	
	/*
	 * Set the Page Links between the current collection Parent page
	 * and the newPages links
	 */
	private void setPageLinks(LinkCollection collection) {		
		Page page = collection.getPage();
		String xpath = collection.getXPath().get();
		if (collection.isList())
			page.addListLink(xpath, newPages);
		else if (collection.isMenu())
			page.addMenuLink(xpath, newPages);
		else if (collection.isSingleton())
			page.addSingleLink(xpath, newPages);
	}
	
	/*
	 * Changes the current LinkCollection XPath version
	 * until it founds different links
	 */
	public void changeXPath() {
		String msg = "getLinks";
		
		boolean finer = collection.isFiner();
		Page page = collection.getPage();
		long urls = page.urlsSize();
		String url = page.getUrl();
		XPath xp = collection.getXPath();
		XPath original = new XPath(xp);
		boolean found = false;

		try {
			HtmlPage html;
			if (page.getTempFile()==null) {
				html = getPage(url, client);
				String directory = FileUtils.getTempDirectory(conf.site);
				String path = HtmlUtils.savePage(html,directory,false);
				page.setTempFile(path);
			} else
				html = HtmlUtils.restorePageFromFile(page.getTempFile(), url);			
			
			while (!found && xp.refine(finer)) {
				List<String> links = getAbsoluteURLs(html, xp.get(), url);
				int size = links.stream().distinct().mapToInt(l -> 1).sum();
				if (!links.equals(collection.getLinks()) && size<urls) {
					collection.setLinks(links);
					log().info("Refined XPath: "+original.get()+" -> "+xp.get());
					found=true;
				}
			}
		} catch (Exception e) {
			log().warning("Failed refinement of XPath: "+e.getMessage());
		}
		
		if (!found) {
			collection.setXPath(original); // restore previous XPath
			if (finer)
				collection.setFinest(true);
			else 
				collection.setCoarsest(true);
			msg = "inspect";
		}
		
		self().tell(msg, self());
	}
	
	/*
	 * Update Model merging candidates to existing classes
	 * or creating new ones.
	 */
	private void updateModel(List<ModelPageClass> candidates) {
		int pages = visitedURLs.size();
		for (ModelPageClass candidate : candidates) {
			WebsiteModel merged = minimumModel(candidate,pages);
			WebsiteModel mNew = new WebsiteModel(model);
			mNew.setPages(pages);
			mNew.addClass(candidate);
			model.copy((merged.cost() < mNew.cost()) ? merged : mNew);
		}
	}
	
	/*
	 * Returns the Merged Model with minimum length cost
	 */
	private WebsiteModel minimumModel(ModelPageClass candidate, int pages) {
		WebsiteModel minimum = null;
		for (ModelPageClass c : model.getClasses()) {
			WebsiteModel temp = new WebsiteModel(model);
			temp.setPages(pages);
			temp.removeClass(c);

			ModelPageClass union = new ModelPageClass(c);
			union.collapse(candidate);
			temp.addClass(union);
			
			if (minimum==null || minimum.cost()>temp.cost())
				minimum = temp;
		}
		return minimum;
	}
	
	public void finalizeModel() {
		log().info("FINALIZING MODEL...");
		client.close();
		if (!model.isEmpty()) {
			PageClass root = model.toGraph(conf);
			root.setHierarchy();
			FileUtils.clearTempDirectory(conf.site);
			log().info("END");
			context().parent().tell(root, self());
		}
		else {
			log().info("MODELING FAILED");
			context().parent().tell(STOP, self());
		}
	}

}
