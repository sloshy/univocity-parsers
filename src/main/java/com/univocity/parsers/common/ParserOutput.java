/*******************************************************************************
 * Copyright 2014 uniVocity Software Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.univocity.parsers.common;

import java.util.*;

import com.univocity.parsers.common.fields.*;
import com.univocity.parsers.common.input.*;

/**
 * The ParserOutput is the component that manages records parsed by {@link AbstractParser} and their values.
 *
 * It is solely responsible for deciding when:
 * <ul>
 * 	<li>parsed records should be reordered according to the fields selected in {@link CommonSettings}</li>
 *  <li>characters and values parsed in {@link AbstractParser#parseRecord()} should be retained or discarded</li>
 *  <li>input headers should be loaded from the records parsed in {@link AbstractParser#parseRecord()} or from {@link CommonSettings#getHeaders()}</li>
 * </ul>
 *
 * Implementations of this class are made available to concrete parser implementations of {@link AbstractParser}.
 *
 * @see AbstractParser
 * @see CommonSettings
 *
 * @author uniVocity Software Pty Ltd - <a href="mailto:parsers@univocity.com">parsers@univocity.com</a>
 *
 */
public class ParserOutput {

	/**
	 * Keeps track of the current column being parsed in the input.
	 * Calls to {@link ParserOutput#valueParsed} and  {@link ParserOutput#emptyParsed} will increase the column count.
	 * Calls to {@link ParserOutput#clear} will reset it to zero.
	 */
	private int column = 0;

	/**
	 * Stores the values parsed for a record.
	 */
	private final String[] parsedValues;

	/**
	 * <p>Stores (shared) references to {@link CharAppender} for each potential column (as given by {@link CommonSettings#getMaxColumns()}).
	 * <p>Fields that are not selected will receive an instance of {@link NoopCharAppender} so all parser calls in {@link AbstractParser#parseRecord()} to {@link ParserOutput#appender} will do nothing.
	 * <p>Selected fields (given by {@link CommonParserSettings}) will receive a functional {@link CharAppender}.
	 */
	private CharAppender[] appenders;

	private final CommonParserSettings<?> settings;
	private final boolean skipEmptyLines;
	private final String nullValue;

	/**
	 * <p>The appender available to parsers for accumulating characters read from the input.
	 * <p>This attribute is assigned to different instances of CharAppender during parsing process, namely, a (potentially) different CharAppender for each parsed column, taken from {@link ParserOutput#appenders}[{@link ParserOutput#column}]
	 */
	public CharAppender appender;
	private boolean columnsToExtractInitialized;
	private boolean columnsReordered;

	private String[] headers;
	private int[] selectedIndexes;

	private int currentRecord;

	/**
	 * Initializes the ParserOutput with the configuration specified in {@link CommonParserSettings}
	 * @param settings the parser configuration
	 */
	public ParserOutput(CommonParserSettings<?> settings) {
		this.appender = settings.newCharAppender();
		this.parsedValues = new String[settings.getMaxColumns()];
		this.appenders = new CharAppender[settings.getMaxColumns()];
		Arrays.fill(appenders, appender);
		this.settings = settings;
		this.skipEmptyLines = settings.getSkipEmptyLines();
		this.nullValue = settings.getNullValue();
		this.columnsToExtractInitialized = false;
		this.currentRecord = 0;
	}

	private void initializeHeaders() {
		this.headers = settings.getHeaders();
		if (headers != null) {
			headers = headers.clone();
			initializeColumnsToExtract(headers);
		} else if (column > 0) { //we only initialize headers from a parsed row if it is not empty
			initializeColumnsToExtract(Arrays.copyOf(parsedValues, column));
			if (settings.isHeaderExtractionEnabled()) {
				headers = new String[column];
				System.arraycopy(parsedValues, 0, headers, 0, column);
			}
		}
	}

	/**
	 * Gets all values parsed in the {@link ParserOutput#parsedValues} array
	 * @return the sequence of parsed values in a record.
	 */
	String[] rowParsed() {
		// some values were parsed. Let's return them
		if (column > 0) {
			// identifies selected columns and headers (in the first non-empty row)
			if (!columnsToExtractInitialized) {
				initializeHeaders();
				//skips the header row. We want to use the headers defined in the settings.
				if (settings.isHeaderExtractionEnabled()) {
					Arrays.fill(parsedValues, null);
					return null;
				}
			}

			currentRecord++;
			if (columnsReordered) {
				String[] reorderedValues = new String[selectedIndexes.length];
				for (int i = 0; i < selectedIndexes.length; i++) {
					int index = selectedIndexes[i];
					if (index >= column) {
						reorderedValues[i] = nullValue;
					} else {
						reorderedValues[i] = parsedValues[index];
					}
				}
				return reorderedValues;
			} else {
				String[] out = new String[column];
				System.arraycopy(parsedValues, 0, out, 0, column);
				return out;
			}
		} else if (!skipEmptyLines) { //no values were parsed, but we are not skipping empty lines
			if (!columnsToExtractInitialized) {
				initializeHeaders();
			}

			currentRecord++;

			if (columnsReordered) {
				String[] out = new String[selectedIndexes.length];
				Arrays.fill(out, nullValue);
				return out;
			}

			return ArgumentUtils.EMPTY_STRING_ARRAY;
		}
		// no values were parsed and we do not care about empty lines.
		return null;
	}

	/**
	 * Initializes the sequence of selected fields, if any.
	 * @param values a sequence of values that represent the headers of the input. This can be either a parsed record or the headers as defined in {@link CommonSettings#getHeaders()}
	 */
	private void initializeColumnsToExtract(String[] values) {
		columnsToExtractInitialized = true;
		columnsReordered = false;
		selectedIndexes = null;
		FieldSelector selector = settings.getFieldSelector();
		if (selector != null) {
			selectedIndexes = selector.getFieldIndexes(values);

			if (selectedIndexes != null) {
				Arrays.fill(appenders, NoopCharAppender.getInstance());

				for (int i = 0; i < selectedIndexes.length; i++) {
					appenders[selectedIndexes[i]] = appender;
				}

				columnsReordered = settings.isColumnReorderingEnabled();
				
				if (!columnsReordered && values.length < appenders.length) {
					Arrays.fill(appenders, values.length, appenders.length, appender);
				}
			}
		}
	}

	/**
	 * Returns the sequence of values that represent the headers each field in the input. This can be either a parsed record or the headers as defined in {@link CommonSettings#getHeaders()}
	 * @return the headers each field in the input
	 */
	public String[] getHeaders() {
		return this.headers;
	}

	/**
	 * Returns the selected indexes of all fields as defined in {@link CommonSettings}. Null if no fields were selected.
	 * @return the selected indexes of all fields as defined in {@link CommonSettings}. Null if no fields were selected.
	 */
	public int[] getSelectedIndexes() {
		return this.selectedIndexes;
	}

	/**
	 *  Indicates whether fields selected using the field selection methods (in {@link CommonSettings}) are being reordered.
	 *
	 * @return
	 * 	<p> false if no fields were selected or column reordering has been disabled in {@link CommonParserSettings#isColumnReorderingEnabled()}
	 * 	<p> true if fields were selected and column reordering has been enabled in {@link CommonParserSettings#isColumnReorderingEnabled()}
	 */
	public boolean isColumnReorderingEnabled() {
		return columnsReordered;
	}

	/**
	 * Returns the position of the current parsed value
	 * @return the position of the current parsed value
	 */
	public int getCurrentColumn() {
		return column;
	}

	/**
	 * Adds a nullValue (as specified in {@link CommonSettings#getNullValue()}) to the output and prepares the next position in the record to receive more values.
	 */
	public void emptyParsed() {
		this.parsedValues[column++] = nullValue;
		this.appender = appenders[column];
	}

	/**
	 * Adds the accumulated value in the appender object to the output and prepares the next position in the record to receive more values.
	 */
	public void valueParsed() {
		this.parsedValues[column++] = appender.getAndReset();
		this.appender = appenders[column];
	}

	/**
	 * Prepares to read the next record by resetting the internal column index to the initial position.
	 */
	void clear() {
		column = 0;
		this.appender = appenders[column];
	}

	/**
	 * Returns the current record index. The number returned here reflects the number of actually parsed and valid records sent to the output of {@link ParserOutput#rowParsed}.
	 * @return the current record index.
	 */
	public int getCurrentRecord() {
		return currentRecord;
	}
}
