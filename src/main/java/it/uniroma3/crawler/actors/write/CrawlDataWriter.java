package it.uniroma3.crawler.actors.write;

import java.nio.file.Files;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.StandardCopyOption;

import org.apache.commons.io.IOUtils;

import com.csvreader.CsvWriter;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import it.uniroma3.crawler.CrawlController;
import it.uniroma3.crawler.model.CrawlURL;

public class CrawlDataWriter extends UntypedActor {
	LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	private CsvWriter csvWriter;
	private CrawlController controller;
	private File baseDirectory;
	private int counter;
	
	public CrawlDataWriter() {
		String fileName = "./result"+getSelf().path().name()+".csv";
		this.csvWriter = new CsvWriter(fileName, '\t', Charset.forName("UTF-8"));
		this.controller = CrawlController.getInstance();
		this.baseDirectory = new File(controller.getBaseDirectory());
		this.counter = 0;
	}

	@Override
	public void onReceive(Object message) throws Throwable {
		if (message instanceof CrawlURL) {
			CrawlURL cUrl = (CrawlURL) message;
			HtmlPage page = cUrl.getPageContent();
			String pageClassName = cUrl.getPageClass().getName();
			String[] record = cUrl.getRecord();
			// send cUrl to scheduler
			controller.getScheduler().forward(cUrl, getContext());
			// save data of interest
			savePage(page, pageClassName);
			saveRecord(record);
		}
		else unhandled(message);
	}
	
	private void savePage(HtmlPage page, String pageClassName) {
		try {
			File directory = new File("html/" +baseDirectory+"/"+ pageClassName);
			if (!directory.exists()) directory.mkdirs();
			InputStream response = page.getWebResponse().getContentAsStream();

			File responseFile = 
					new File(directory.toString() + "/" + getPageFileName(pageClassName));

			Files.copy(response, 
					responseFile.toPath(), 
					StandardCopyOption.REPLACE_EXISTING);

			IOUtils.closeQuietly(response);
		} catch (IOException e) {
			log.error("Can't save Html page");
		}
	}
	
	private void saveRecord(String[] record) {
		if (record!=null) {
			try {
				csvWriter.writeRecord(record);
			} catch (IOException e) {
				log.error("Can't save record to csv");
			}
		}
	}
	
	private String getPageFileName(String pageClassName) {
		return pageClassName+"_"+ (++counter) +".html";
	}
	
	@Override
	public void postStop() {
		this.csvWriter.close();
	}

}
