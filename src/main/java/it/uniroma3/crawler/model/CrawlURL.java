package it.uniroma3.crawler.model;

import static java.util.stream.Collectors.toSet;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class CrawlURL implements Comparable<CrawlURL> {
	private URI url;
	private PageClass pageClass;
	private String filePath;
	private Map<String, String> outLinks;
	private String[] record;
	
	public CrawlURL(String url, PageClass pageClass) throws URISyntaxException {
		this(URI.create(url), pageClass);
	}
	
	public CrawlURL(URI uri, PageClass pageClass) {
		this.url = uri;
		this.pageClass = pageClass;
		this.outLinks = new HashMap<>();
	}
	
	public URI getUrl() {
		return url;
	}
	
	public String getStringUrl() {
		return url.toString();
	}
	
	public String getDomain() {
		return pageClass.getDomain();
	}
	
	public PageClass getPageClass() {
		return this.pageClass;
	}
	
	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public void addOutLink(String link, String pClass) {
		this.outLinks.putIfAbsent(link, pClass);
	}
	
	public Set<String> getOutLinks() {
		return outLinks.keySet().stream().collect(toSet());
	}
	
	public String getOutLinkPageClass(String link) {
		return outLinks.get(link);
	}
	
	public void setRecord(String[] record) {
		this.record = record;
	}
	
	public String[] getRecord() {
		return this.record;
	}
	
	public String getRelativeUrl() {
		String path = url.getPath();
		String query = url.getQuery();
		if (path==null || path.equals("/")) 
			return "";
		if (query==null) 
			return path;
		return path+query;
	}
	
	public int compareTo(CrawlURL c2) {
		int cmpPc = this.getPageClass().compareTo(c2.getPageClass());
		if (cmpPc!=0) return cmpPc;
		return url.compareTo(c2.getUrl());
	}
	
	public String toString() {
		return "[URL: "+url.toString()+", CLASS: "+pageClass.getName()+"]";
	}
	
	public int hashCode() {
		return url.hashCode() + pageClass.hashCode();
	}

	public boolean equals(Object obj) {
		if (obj instanceof CrawlURL) {
			CrawlURL other = (CrawlURL) obj;
			return Objects.equals(url, other.getUrl())
					&& Objects.equals(pageClass, other.getPageClass());
		}
		return false;
	}
	
}
