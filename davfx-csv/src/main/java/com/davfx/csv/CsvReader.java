package com.davfx.csv;

import java.io.IOException;

public interface CsvReader {
	String skip() throws IOException;
	Iterable<String> next() throws IOException;
}
