package it.uniroma3.crawler.modeler;

import static it.uniroma3.crawler.util.HtmlUtils.*;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static it.uniroma3.crawler.util.XPathUtils.getAbsoluteURLs;

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
import it.uniroma3.crawler.model.PageClass;
import it.uniroma3.crawler.modeler.model.ModelPageClass;
import it.uniroma3.crawler.modeler.model.LinkCollection;
import it.uniroma3.crawler.modeler.model.Page;
import it.uniroma3.crawler.modeler.model.WebsiteModel;
import it.uniroma3.crawler.modeler.model.XPath;
import it.uniroma3.crawler.settings.CrawlerSettings.SeedConfig;
import it.uniroma3.crawler.util.Commands;
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
	
	/**
	 * a reference to the ModelPageClass id where
	 * the last singleton newPage was added.
	 */
	private int lastSingletonId;
	
	/**
	 * counter of how many times a singleton newPage
	 * was added to the same ModelPageClass
	 */
	private int singletonCounter;
	
	@Override
	public Receive createReceive() {
		return receiveBuilder()
		.match(SeedConfig.class, this::start)
		.matchEquals("poll", msg -> poll())
		.matchEquals("getLinks", msg -> getLinks())
		.matchEquals("fetch", msg -> fetch())
		.matchEquals("cluster", msg -> cluster())
		.matchEquals("refine", msg -> changeXPath())
		.matchEquals("update", msg -> update())
		.matchEquals("finalize", msg -> finalizeModel())
		.build();
	}
	
	public void start(SeedConfig sc) {
		conf = sc;
		client = makeWebClient(sc.javascript);

		// Feed queue with seed
		queue.add(new LinkCollection(Arrays.asList(conf.site)));
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
		links = collection.getLinksToFetch();
		
		if (newPages.stream().anyMatch(p -> !p.isLoaded())) // if some page was downloaded, wait
			context().system().scheduler().scheduleOnce(
					Duration.create(conf.wait, TimeUnit.MILLISECONDS), 
					self(), "fetch", context().dispatcher(), self());
		else self().tell("fetch", self());		
		
		/* reset pages */
		newPages.clear();
	}
	
	public void fetch() {
		if (!links.isEmpty()) {
			String url = links.poll();
			if (isValidURL(conf.site, url)) {
				try {
					Page page = visitedURLs.get(url);
					if (page!=null) {
						log().info("Loaded: "+url);
						page.setLoaded();
					}
					else if (visitedURLs.size()<conf.modelPages) {
						page = new Page(url, getPage(url, client));
						visitedURLs.put(url, page);
						log().info("Fetched: "+url);
					}
					else {
						self().tell("finalize", self());
						return;
					}
					newPages.add(page);
				} catch (Exception e) {
					log().warning("Failed fetching: "+url+", "+e.getMessage());
				}
			}
			else log().info("Rejected URL: "+url);
			
			self().tell("fetch", self());
		}
		else if (!newPages.isEmpty())
			self().tell("cluster", self());
		else 
			self().tell("poll", self());
	}
	
	/*
	 * Cluster newPages
	 * Inspect the candidates and take actions on the base of:
	 * - Number of newPages fetched
	 * - Number of clusters created
	 */
	public void cluster() {
		candidates = clusterPages(newPages);
		
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
		else if (newPages.size()==1)
			collection.setSingleton();
		
		self().tell(msg, self());
	}
	
	/* 
	 * Candidate classes selection
	 * Collapse classes with similar structure
	 */
	private List<ModelPageClass> clusterPages(List<Page> pages) {
		List<ModelPageClass> candidates =
			pages.stream()
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
		return candidates;
	}
	
	public void update() {
		String msg = "poll";
		
		List<ModelPageClass> toRemove = new ArrayList<>();
		for (ModelPageClass c : candidates) {
			for (Page p : c.getPages()) {
				/* If there are already classified pages in candidates
				 * we should skip the update phase for this cluster,
				 * merging new fetched pages. */
				if (p.isClassified()) {
					ModelPageClass mpc = model.getClassOfPage(p);
					mpc.collapse(c); // merge new fetched pages, if any
					toRemove.add(c);
					break;
				}
			}
		}
		candidates.removeAll(toRemove);
		
		updateModel(candidates);
		
		if (setPageLinks(collection)) {
			newPages.stream()
					.filter(p -> !p.classified())
					.map(Page::getLinkCollections)
					.flatMap(Set::stream)
					.forEach(queue::add);
		}
		else msg = "refine"; 
			
		self().tell(msg, self());
	}
	
	/*
	 * Set the Page Links between the current collection Parent page
	 * and the newPages links
	 * Returns false if an XPath refinement is required
	 */
	private boolean setPageLinks(LinkCollection collection) {
		boolean saved = true;
		
		Page page = collection.getPage();
		
		// seed does not have a parent page
		if (page!=null) { 
			String xpath = collection.getXPath().get();
			if (collection.isList())
				page.addListLink(xpath, newPages);
			else if (collection.isMenu())
				page.addMenuLink(xpath, newPages);
			else if (collection.isSingleton()) {
				int classID = model.getClassOfPage(newPages.get(0)).getId();
				if (classID==lastSingletonId && singletonCounter==3
						&& !collection.isCoarsest()) {
					singletonCounter = 0;
					saved = false;
				}
				else {
					if (classID==lastSingletonId) 
						singletonCounter++;
					page.addSingleLink(xpath, newPages);
				}
			}
		}
		return saved;
	}
	
	/*
	 * Changes the current LinkCollection XPath version
	 * until it founds different links
	 */
	public void changeXPath() {
		boolean finer = collection.isFiner();
		Page page = collection.getPage();
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
			
			while (!found && !(xp.refine(finer)).isEmpty()) {
				List<String> links = getAbsoluteURLs(html, xp.get(), url);
				if (!links.equals(collection.getLinks())) {
					collection.setLinks(links);
					log().info("Refined XPath: "+xp.getDefault()+" -> "+xp.get());
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
		}
		
		self().tell("getLinks", self());
	}
	
	/*
	 * Update Model merging candidates to existing classes
	 * or creating new ones.
	 */
	private void updateModel(List<ModelPageClass> candidates) {
		for (ModelPageClass candidate : candidates) {
			WebsiteModel merged = minimumModel(candidate);
			WebsiteModel mNew = new WebsiteModel(model);
			mNew.addClass(candidate);			
			model.copy((merged.cost() < mNew.cost()) ? merged : mNew);
		}
	}
	
	/*
	 * Returns the Merged Model with minimum length cost
	 */
	private WebsiteModel minimumModel(ModelPageClass candidate) {
		WebsiteModel minimum = new WebsiteModel();
		for (ModelPageClass c : model.getClasses()) {
			WebsiteModel temp = new WebsiteModel(model);
			temp.removeClass(c);

			ModelPageClass union = new ModelPageClass(c.getId());
			union.collapse(candidate);
			union.collapse(c);
			temp.addClass(union);

			if (minimum.cost()>temp.cost())
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
			context().parent().tell(Commands.STOP, self());
		}
	}

}
