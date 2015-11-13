package com.davfx.csv;

public interface MayAutoCloseCsvReader extends CsvReader {
	AutoCloseableCsvReader autoClose();
}
