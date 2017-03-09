package it.uniroma3.crawler.writer;

import java.nio.charset.Charset;

import com.csvreader.CsvWriter;

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.japi.Creator;

public class Writer extends UntypedActor {
	
	public static Props props(String fileName) {
		return Props.create(new Creator<Writer>() {
			private static final long serialVersionUID = 1L;

			@Override
			public Writer create() throws Exception {
				return new Writer(fileName);
			}
		});
	}

	final CsvWriter writer;
	
	public Writer(String fileName) {
		this.writer = new CsvWriter(fileName, ',', Charset.forName("UTF-8"));
	}

	@Override
	public void onReceive(Object message) throws Throwable {
		if (message instanceof String[]) {
			String[] record = (String[]) message;
			this.writer.writeRecord(record);
			this.writer.close();
		}
		else unhandled(message);
	}

}
