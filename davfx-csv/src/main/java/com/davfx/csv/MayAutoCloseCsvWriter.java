package com.davfx.csv;

public interface MayAutoCloseCsvWriter extends CsvWriter {
	AutoCloseableCsvWriter autoClose();
}
