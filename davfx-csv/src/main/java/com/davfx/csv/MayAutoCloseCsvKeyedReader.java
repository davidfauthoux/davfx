package com.davfx.csv;

public interface MayAutoCloseCsvKeyedReader extends CsvKeyedReader {
	AutoCloseableCsvKeyedReader autoClose();
}
