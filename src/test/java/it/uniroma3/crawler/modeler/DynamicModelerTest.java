package it.uniroma3.crawler.modeler;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.javadsl.TestKit;
import it.uniroma3.crawler.messages.ModelMsg;
import it.uniroma3.crawler.model.PageClass;
import it.uniroma3.crawler.settings.CrawlerSettings.SeedConfig;

public class DynamicModelerTest {
	static ActorSystem system;

	@BeforeClass
	public static void setup() {
		system = ActorSystem.create("ModelerTest");
	}

	@AfterClass
	public static void tearDownAfterClass() {
		TestKit.shutdownActorSystem(system);
		system = null;
	}
	
	@After
	public void tearDown() throws IOException {
		Files.delete(Paths.get("src/main/resources/targets/localhost_8081_target.csv"));
	}
	 
	@Test
	public void testDynamicModeler_localhost() {
		new TestKit(system) {{
			final ActorRef crawlModeler = system.actorOf(Props.create(CrawlModeler.class));
			final ActorRef probe = getRef();
				
			SeedConfig conf = new SeedConfig("null", 10, false, 0, 0, 1);

			crawlModeler.tell(new ModelMsg("http://localhost:8081", conf), probe);

			final PageClass home = expectMsgClass(duration("60 seconds"), PageClass.class);
			
			String toDirectory = "(//ul[@id='menu']/li/a)[1]";
			String toNext = "//a[@id='page']";
			PageClass directory1 = home.getDestinationByXPath(toDirectory);
			
			assertEquals("class1", home.getName());
			assertEquals(0, home.getDepth());
			assertTrue(home.getMenuXPaths().contains(toDirectory));

			assertEquals(1, directory1.getDepth());
			assertEquals(directory1, directory1.getDestinationByXPath(toNext));
			assertTrue(directory1.getListXPaths().size() > 0);
		}};
	}

}
