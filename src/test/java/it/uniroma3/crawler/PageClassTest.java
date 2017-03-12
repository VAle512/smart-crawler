package it.uniroma3.crawler;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import it.uniroma3.crawler.model.ImgDataType;
import it.uniroma3.crawler.model.PageClass;
import it.uniroma3.crawler.model.UrlDataType;

public class PageClassTest {
	private PageClass page;

	@Before
	public void setUp() throws Exception {
		this.page = new PageClass("source", 1000);
	}
	
	@Test
	public void testAddLink_creation() {
		PageClass dest = new PageClass("destination", 1000);
		String xpath = "//li[@class='dropdown menu-jobs-directories']//a[text()='Companies']";
		assertTrue(page.addLink(xpath, dest));
	}
	
	@Test
	public void testAddData_string() {
		String xpath = "//h1/text()";
		String type = "string";
		assertTrue(page.addData(xpath, type));
	}
	
	@Test
	public void testAddData_url() {
		String xpath = "//div[@class='rdbx']/div[@class='contact_column']/a[@target]";
		String type = "url";
		assertTrue(page.addData(xpath, type));
	}
	
	@Test
	public void testAddData_image() {
		String xpath = "//img[@alt='logo']";
		String type = "img";
		assertTrue(page.addData(xpath, type));
	}

	@Test
	public void testGetDestinationByXPath_found() {
		PageClass dest = new PageClass("destination", 1000);
		String xpath = "//li[@class='dropdown menu-jobs-directories']//a[text()='Companies']";
		page.addLink(xpath, dest);
		assertEquals(page.getDestinationByXPath(xpath), dest);
	}
	
	@Test
	public void testGetDestinationByXPath_multipleElementsFound() {
		PageClass dest = new PageClass("destination", 1000);
		PageClass dest2 = new PageClass("destination2", 1000);
		PageClass dest3 = new PageClass("destination3", 1000);
		page.addLink("//ul", dest);
		page.addLink("//li", dest2);
		page.addLink("//a", dest3);
		assertEquals(page.getDestinationByXPath("//li"), dest2);
	}
	
	@Test
	public void testGetDestinationByXPath_notFound() {
		PageClass dest = new PageClass("destination", 1000);
		String xpath = "//li[@class='dropdown menu-jobs-directories']//a[text()='Companies']";
		page.addLink(xpath, dest);
		assertNull(page.getDestinationByXPath("//li[@class='dropdown']"));
	}

	@Test
	public void testGetDataTypeByXPath_foundImg() {
		String xpath = "//img[@alt='logo']";
		String type = "img";
		page.addData(xpath, type);
		assertTrue(page.getDataTypeByXPath("//img[@alt='logo']") instanceof ImgDataType);
	}
	
	@Test
	public void testGetDataTypeByXPath_foundString() {
		String xpath = "//div";
		String type = "url";
		page.addData(xpath, type);
		assertTrue(page.getDataTypeByXPath("//div") instanceof UrlDataType);
	}
	
	@Test
	public void testGetDataTypeByXPath_notFound() {
		page.addData("//div", "url");
		page.addData("//li", "string");
		page.addData("//ul", "img");
		page.addData("//a", "url");
		assertEquals(page.getDataTypeByXPath("//tr"), null);
	}
	
	@Test
	public void testgetXPaths() {
		PageClass dest = new PageClass("destination", 1000);
		PageClass dest2 = new PageClass("destination2", 1000);
		PageClass dest3 = new PageClass("destination3", 1000);
		List<String> xpaths = new ArrayList<>();
		String xpath1 = "//ul";
		String xpath2 = "//li";
		String xpath3 = "//a";
		xpaths.add(xpath1);
		xpaths.add(xpath2);
		xpaths.add(xpath3);
		page.addLink(xpath1, dest);
		page.addLink(xpath2, dest2);
		page.addLink(xpath3, dest3);
		
		List<String> pageXPaths = page.getNavigationXPaths();
		for (int i=0; i<pageXPaths.size(); i++) {
			assertEquals(pageXPaths.get(i), xpaths.get(i));
		}
	}

}
