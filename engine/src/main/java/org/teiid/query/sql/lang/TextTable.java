/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.query.sql.lang;

import java.util.ArrayList;
import java.util.List;

import org.teiid.core.types.DataTypeManager;
import org.teiid.core.util.EquivalenceUtil;
import org.teiid.query.sql.LanguageVisitor;
import org.teiid.query.sql.symbol.Expression;

/**
 * Represents the TEXTTABLE table function.
 */
public class TextTable extends TableFunctionReference {
	
	public static class TextColumn extends ProjectedColumn {
		private Integer width;
		private boolean noTrim;
		private String selector;
		private Integer position;
		private boolean ordinal;
		private String header;
		
		public TextColumn(String name) {
			super(name, DataTypeManager.DefaultDataTypes.INTEGER);
			this.ordinal = true;
		}
		
		public TextColumn(String name, String type, Integer width, boolean noTrim) {
			super(name, type);
			this.width = width;
			this.noTrim = noTrim;
		}
		
		protected TextColumn() {
			
		}
		
		public Integer getWidth() {
			return width;
		}
		
		public void setWidth(Integer width) {
			this.width = width;
		}
		
		public boolean isNoTrim() {
			return noTrim;
		}
		
		public void setNoTrim(boolean noTrim) {
			this.noTrim = noTrim;
		}
		
		public String getHeader() {
			return header;
		}
		
		public void setHeader(String header) {
			this.header = header;
		}
		
		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (!super.equals(obj) || !(obj instanceof TextColumn)) {
				return false;
			}
			TextColumn other = (TextColumn)obj;
			return EquivalenceUtil.areEqual(width, other.width)
			&& EquivalenceUtil.areEqual(selector, other.selector)
			&& EquivalenceUtil.areEqual(position, other.position)
			&& noTrim == other.noTrim
			&& ordinal == other.ordinal
			&& EquivalenceUtil.areEqual(header, other.header);
		}
		
		@Override
		public TextColumn clone() {
			TextColumn clone = new TextColumn();
			clone.width = this.width;
			clone.noTrim = this.noTrim;
			clone.selector = this.selector;
			clone.position = this.position;
			clone.ordinal = this.ordinal;
			clone.header = this.header;
			this.copyTo(clone);
			return clone;
		}

		public String getSelector() {
			return selector;
		}
		
		public void setSelector(String selector) {
			this.selector = selector;
		}
		
		public Integer getPosition() {
			return position;
		}
		
		public void setPosition(Integer position) {
			this.position = position;
		}
		
		public boolean isOrdinal() {
			return ordinal;
		}
		
	}
	
    private Expression file;
    private List<TextColumn> columns = new ArrayList<TextColumn>();
    private Character rowDelimiter;
	private Character delimiter;
	private Character quote;
    private boolean escape;
    private Integer header;
    private Integer skip;
    private boolean usingRowDelimiter = true;
    private String selector;
    
    private boolean fixedWidth;
    
    public String getSelector() {
		return selector;
	}
    
    public void setSelector(String selector) {
		this.selector = selector;
	}
    
    public Character getQuote() {
		return quote;
	}
    
    public void setQuote(Character quote) {
		this.quote = quote;
	}
    
    public boolean isEscape() {
		return escape;
	}
    
    public void setEscape(boolean escape) {
		this.escape = escape;
	}
    
    public boolean isFixedWidth() {
		return fixedWidth;
	}
    
    public void setFixedWidth(boolean fixedWidth) {
		this.fixedWidth = fixedWidth;
	}
    
    public List<TextColumn> getColumns() {
		return columns;
	}
    
    public void setColumns(List<TextColumn> columns) {
		this.columns = columns;
	}
    
    public Character getRowDelimiter() {
		return rowDelimiter;
	}
    
    public void setRowDelimiter(Character rowDelimiter) {
		this.rowDelimiter = rowDelimiter;
	}
    
    public Character getDelimiter() {
		return delimiter;
	}

	public void setDelimiter(Character delimiter) {
		this.delimiter = delimiter;
	}

	public Integer getHeader() {
		return header;
	}

	public void setHeader(Integer header) {
		this.header = header;
	}

	public Integer getSkip() {
		return skip;
	}

	public void setSkip(Integer skip) {
		this.skip = skip;
	}
    
    public Expression getFile() {
		return file;
	}
    
    public void setFile(Expression file) {
		this.file = file;
	}
    
    public boolean isUsingRowDelimiter() {
		return usingRowDelimiter;
	}
    
    public void setUsingRowDelimiter(boolean usingRowDelimiter) {
		this.usingRowDelimiter = usingRowDelimiter;
	}
    
    public void setNoTrim() {
    	for (TextColumn col : columns) {
    		col.noTrim = true;
    	}
    }
    
    public boolean isNoTrim() {
    	for (TextColumn col : columns) {
    		if (!col.noTrim) {
    			return false;
    		}
    	}
    	return true;
    }

	@Override
	public void acceptVisitor(LanguageVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	protected TextTable cloneDirect() {
		TextTable clone = new TextTable();
		this.copy(clone);
		clone.setDelimiter(this.delimiter);
		clone.setFile((Expression)this.file.clone());
		clone.setHeader(this.header);
		clone.setSkip(this.skip);
		clone.setQuote(this.quote);
		clone.escape = this.escape;
		for (TextColumn column : columns) {
			clone.getColumns().add(column.clone());
		}
		clone.fixedWidth = this.fixedWidth;
		clone.usingRowDelimiter = this.usingRowDelimiter;
		clone.rowDelimiter = this.rowDelimiter;
		clone.selector = this.selector;
		return clone;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		if (!super.equals(obj) || !(obj instanceof TextTable)) {
			return false;
		}
		TextTable other = (TextTable)obj;
		return this.columns.equals(other.columns) 
			&& EquivalenceUtil.areEqual(file, other.file)
			&& EquivalenceUtil.areEqual(delimiter, other.delimiter)
			&& EquivalenceUtil.areEqual(escape, other.escape)
			&& EquivalenceUtil.areEqual(quote, other.quote)
			&& EquivalenceUtil.areEqual(header, other.header)
			&& EquivalenceUtil.areEqual(skip, other.skip)
			&& usingRowDelimiter == other.usingRowDelimiter
			&& EquivalenceUtil.areEqual(selector, other.selector)
			&& EquivalenceUtil.areEqual(rowDelimiter, other.rowDelimiter);
		
	}
	
}
