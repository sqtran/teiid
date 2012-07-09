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

package org.teiid.query.function.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.EventFilter;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.Location;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathExpressionException;

import net.sf.saxon.expr.JPConverter;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.Name11Checker;
import net.sf.saxon.sxpath.XPathEvaluator;
import net.sf.saxon.sxpath.XPathExpression;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.AtomicValue;
import net.sf.saxon.value.DateTimeValue;
import net.sf.saxon.value.DateValue;
import net.sf.saxon.value.DayTimeDurationValue;
import net.sf.saxon.value.TimeValue;

import org.json.simple.parser.ContentHandler;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.teiid.common.buffer.BufferManager;
import org.teiid.common.buffer.FileStore;
import org.teiid.common.buffer.FileStoreInputStreamFactory;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.TeiidProcessingException;
import org.teiid.core.TeiidRuntimeException;
import org.teiid.core.types.ClobImpl;
import org.teiid.core.types.ClobType;
import org.teiid.core.types.SQLXMLImpl;
import org.teiid.core.types.Streamable;
import org.teiid.core.types.XMLTranslator;
import org.teiid.core.types.XMLType;
import org.teiid.core.types.XMLType.Type;
import org.teiid.jdbc.TeiidSQLException;
import org.teiid.query.QueryPlugin;
import org.teiid.query.eval.Evaluator;
import org.teiid.query.function.CharsetUtils;
import org.teiid.query.util.CommandContext;
import org.teiid.translator.WSConnection.Util;


/** 
 * This class contains scalar system functions supporting for XML manipulation.
 * 
 * @since 4.2
 */
public class XMLSystemFunctions {
	
	private static final Charset UTF_32BE = Charset.forName("UTF-32BE"); //$NON-NLS-1$
	private static final Charset UTF_16BE = Charset.forName("UTF-16BE"); //$NON-NLS-1$
	private static final Charset UTF_32LE = Charset.forName("UTF-32LE"); //$NON-NLS-1$
	private static final Charset UTF_16LE = Charset.forName("UTF-16LE"); //$NON-NLS-1$
	private static final Charset UTF_8 = Charset.forName("UTF-8"); //$NON-NLS-1$

	private static final Location dummyLocation = new Location() {
		@Override
		public String getSystemId() {
			return null;
		}

		@Override
		public String getPublicId() {
			return null;
		}

		@Override
		public int getLineNumber() {
			return -1;
		}

		@Override
		public int getColumnNumber() {
			return -1;
		}

		@Override
		public int getCharacterOffset() {
			return -1;
		}
	};

	private static final class JsonToXmlContentHandler implements
			ContentHandler, XMLEventReader {
		private Reader reader;
		private JSONParser parser;
		private XMLEventFactory eventFactory;

		private LinkedList<String> nameStack = new LinkedList<String>();
		private LinkedList<XMLEvent> eventStack = new LinkedList<XMLEvent>();
		
		private boolean rootArray;
		private boolean end;
		private boolean declaredNs;

		private JsonToXmlContentHandler(String rootName,
				Reader reader, JSONParser parser, XMLEventFactory eventFactory) {
			this.nameStack.push(escapeName(rootName, true));
			this.reader = reader;
			this.eventFactory = eventFactory;
			this.parser = parser;
		}

		@Override
		public boolean startObjectEntry(String key)
				throws org.json.simple.parser.ParseException, IOException {
			this.nameStack.push(escapeName(key, true));
			return false;
		}

		@Override
		public boolean startObject() throws org.json.simple.parser.ParseException,
				IOException {
			start();
			return false;
		}

		private void start() {
			eventStack.add(eventFactory.createStartElement("", "", nameStack.peek())); //$NON-NLS-1$ //$NON-NLS-2$ 
			if (!declaredNs) {
				eventStack.add(eventFactory.createNamespace("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI)); //$NON-NLS-1$
				declaredNs = true;
			}
		}

		@Override
		public void startJSON() throws org.json.simple.parser.ParseException,
				IOException {
			//specify the defaults, since different providers emit/omit differently
			eventStack.add(eventFactory.createStartDocument("UTF-8", "1.0")); //$NON-NLS-1$ //$NON-NLS-2$
		}

		@Override
		public boolean startArray() throws org.json.simple.parser.ParseException,
				IOException {
			if (this.nameStack.size() == 1) {
				this.rootArray = true;
				start();
			}
			return false;
		}

		@Override
		public boolean primitive(Object value)
				throws org.json.simple.parser.ParseException, IOException {
			start();
			if (value != null) {
				String type = "decimal"; //$NON-NLS-1$
				if (value instanceof String) {
					type = null;
				} else if (value instanceof Boolean) {
					type = "boolean"; //$NON-NLS-1$
				}
				if (type != null) {
					//we need to differentiate boolean/decimal entries from their string counter parts
					eventStack.add(eventFactory.createAttribute("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type", type)); //$NON-NLS-1$ //$NON-NLS-2$
				}
				eventStack.add(eventFactory.createCharacters(value.toString()));
			} else {
				eventStack.add(eventFactory.createAttribute("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			end();
			return true; //return true, otherwise we don't get the endObjectEntry
		}

		private void end() {
			eventStack.add(eventFactory.createEndElement("", "", nameStack.peek())); //$NON-NLS-1$ //$NON-NLS-2$ 
		}

		@Override
		public boolean endObjectEntry()
				throws org.json.simple.parser.ParseException, IOException {
			this.nameStack.pop();
			return false;
		}

		@Override
		public boolean endObject() throws org.json.simple.parser.ParseException,
				IOException {
			end();
			return false;
		}

		@Override
		public void endJSON() throws org.json.simple.parser.ParseException,
				IOException {
			this.eventStack.add(eventFactory.createEndDocument());
			end = true;
		}

		@Override
		public boolean endArray() throws org.json.simple.parser.ParseException,
				IOException {
			if (this.nameStack.size() == 1 && rootArray) {
				end();
			}
			return false;
		}

		@Override
		public void close() throws XMLStreamException {
			try {
				//this is explicitly against the javadoc, but
				//it's our only chance to close the reader
				this.reader.close();
			} catch (IOException e) {
			}
		}

		@Override
		public String getElementText() throws XMLStreamException {
			throw new UnsupportedOperationException();
		}

		@Override
		public Object getProperty(String name) throws IllegalArgumentException {
			return null;
		}

		@Override
		public boolean hasNext() {
			return !eventStack.isEmpty() || !end;
		}

		@Override
		public XMLEvent nextEvent() throws XMLStreamException {
			while (eventStack.isEmpty() && !end) {
				try {
					parser.parse(reader, this, true);
				} catch (IOException e) {
					throw new XMLStreamException(e);
				} catch (ParseException e) {
					throw new XMLStreamException(e);
				}
			}
			return eventStack.remove();
		}

		@Override
		public XMLEvent nextTag() throws XMLStreamException {
			throw new UnsupportedOperationException();
		}

		@Override
		public XMLEvent peek() throws XMLStreamException {
			if (hasNext()) {
				XMLEvent next = next();
				this.eventStack.push(next);
				return next;
			}
			return null;
		}

		@Override
		public XMLEvent next() {
			try {
				return nextEvent();
			} catch (XMLStreamException e) {
				throw new TeiidRuntimeException(e);
			}
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();			
		}
	}
	
	private static ThreadLocal<TransformerFactory> threadLocalTransformerFactory = new ThreadLocal<TransformerFactory>() {
		protected TransformerFactory initialValue() {
			return TransformerFactory.newInstance();
		}
	};
	static ThreadLocal<XMLOutputFactory> threadLocalOutputFactory = new ThreadLocal<XMLOutputFactory>() {
		protected XMLOutputFactory initialValue() {
			return newXmlOutputFactory();
		}
	};
	static ThreadLocal<XMLEventFactory> threadLocalEventtFactory = new ThreadLocal<XMLEventFactory>() {
		protected XMLEventFactory initialValue() {
			return XMLEventFactory.newFactory();
		}
		public XMLEventFactory get() {
			XMLEventFactory eventFactory = super.get();
			eventFactory.setLocation(null);
			return eventFactory;
		}
	};
	private static final String P_OUTPUT_VALIDATE_STRUCTURE = "com.ctc.wstx.outputValidateStructure"; //$NON-NLS-1$
	static XMLOutputFactory newXmlOutputFactory() throws FactoryConfigurationError {
		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		if (factory.isPropertySupported(P_OUTPUT_VALIDATE_STRUCTURE)) {
			factory.setProperty(P_OUTPUT_VALIDATE_STRUCTURE, false);
		}
		return factory;
	}
	static XMLOutputFactory xmlOutputFactory = newXmlOutputFactory();
	
	public static XMLOutputFactory getOutputFactory() throws FactoryConfigurationError {
		if (XMLType.isThreadSafeXmlFactories()) {
			return xmlOutputFactory;
		}
		return threadLocalOutputFactory.get();
	}

	public static ClobType xslTransform(CommandContext context, Object xml, Object styleSheet) throws Exception {
    	Source styleSource = null; 
		Source xmlSource = null;
		try {
			styleSource = convertToSource(styleSheet);
			xmlSource = convertToSource(xml);
			final Source xmlParam = xmlSource;
			TransformerFactory factory = threadLocalTransformerFactory.get();
            final Transformer transformer = factory.newTransformer(styleSource);
            
			//this creates a non-validated sqlxml - it may not be valid xml/root-less xml
			SQLXMLImpl result = XMLSystemFunctions.saveToBufferManager(context.getBufferManager(), new XMLTranslator() {
				
				@Override
				public void translate(Writer writer) throws TransformerException {
	                //transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes"); //$NON-NLS-1$
	                // Feed the resultant I/O stream into the XSLT processor
					transformer.transform(xmlParam, new StreamResult(writer));
				}
			});
			return new ClobType(new ClobImpl(result.getStreamFactory(), -1));
		} finally {
			Util.closeSource(styleSource);
			Util.closeSource(xmlSource);
		}
    }

	public static XMLType xmlForest(final CommandContext context, final Evaluator.NameValuePair[] namespaces, final Evaluator.NameValuePair[] values) throws TeiidComponentException, TeiidProcessingException {
		boolean valueExists = false;
		for (Evaluator.NameValuePair nameValuePair : values) {
			if (nameValuePair.value != null) {
				valueExists = true;
				break;
			}
		}
		if (!valueExists) {
			return null;
		}

		XMLType result = new XMLType(XMLSystemFunctions.saveToBufferManager(context.getBufferManager(), new XMLTranslator() {
			
			@Override
			public void translate(Writer writer) throws TransformerException,
					IOException {
				try {
					XMLOutputFactory factory = getOutputFactory();
					XMLEventWriter eventWriter = factory.createXMLEventWriter(writer);
					XMLEventFactory eventFactory = threadLocalEventtFactory.get();
					for (Evaluator.NameValuePair nameValuePair : values) {
						if (nameValuePair.value == null) {
							continue;
						}
						addElement(nameValuePair.name, writer, eventWriter, eventFactory, namespaces, null, Collections.singletonList(nameValuePair.value));
					}
					eventWriter.close();
				} catch (XMLStreamException e) {
					throw new TransformerException(e);
				} 
			}
		}));
		result.setType(Type.CONTENT);
		return result;
	}
	
	/**
	 * Basic support for xmlelement.  namespaces are not yet supported.
	 * @param context
	 * @param name
	 * @param contents
	 * @return
	 * @throws TeiidComponentException
	 * @throws TeiidProcessingException 
	 */
	public static XMLType xmlElement(CommandContext context, final String name, 
			final Evaluator.NameValuePair<String>[] namespaces, final Evaluator.NameValuePair<?>[] attributes, final List<?> contents) throws TeiidComponentException, TeiidProcessingException {
		XMLType result = new XMLType(XMLSystemFunctions.saveToBufferManager(context.getBufferManager(), new XMLTranslator() {
			
			@Override
			public void translate(Writer writer) throws TransformerException,
					IOException {
				try {
					XMLOutputFactory factory = getOutputFactory();
					XMLEventWriter eventWriter = factory.createXMLEventWriter(writer);
					XMLEventFactory eventFactory = threadLocalEventtFactory.get();
					addElement(name, writer, eventWriter, eventFactory, namespaces, attributes, contents);
					eventWriter.close();
				} catch (XMLStreamException e) {
					throw new TransformerException(e);
				} 
			}

		}));
		result.setType(Type.ELEMENT);
		return result;
	}
	
	private static void addElement(final String name, Writer writer, XMLEventWriter eventWriter, XMLEventFactory eventFactory,
			Evaluator.NameValuePair<String> namespaces[], Evaluator.NameValuePair<?> attributes[], List<?> contents) throws XMLStreamException, IOException, TransformerException {
		eventWriter.add(eventFactory.createStartElement("", null, name)); //$NON-NLS-1$
		if (namespaces != null) {
			for (Evaluator.NameValuePair<String> nameValuePair : namespaces) {
				if (nameValuePair.name == null) {
					if (nameValuePair.value == null) {
						eventWriter.add(eventFactory.createNamespace(XMLConstants.NULL_NS_URI));
					} else {
						eventWriter.add(eventFactory.createNamespace(nameValuePair.value));
					} 
				} else {
					eventWriter.add(eventFactory.createNamespace(nameValuePair.name, nameValuePair.value));
				}
			}
		}
		if (attributes != null) {
			for (Evaluator.NameValuePair<?> nameValuePair : attributes) {
				if (nameValuePair.value != null) {
					eventWriter.add(eventFactory.createAttribute(new QName(nameValuePair.name), convertToAtomicValue(nameValuePair.value).getStringValue()));
				}
			}
		}
		//add empty chars to close the start tag
		eventWriter.add(eventFactory.createCharacters("")); //$NON-NLS-1$ 
		for (Object object : contents) {
			convertValue(writer, eventWriter, eventFactory, object);
		}
		eventWriter.add(eventFactory.createEndElement("", null, name)); //$NON-NLS-1$
	}
	
	public static XMLType xmlConcat(CommandContext context, final XMLType xml, final Object... other) throws TeiidProcessingException {
		//determine if there is just a single xml value and return it
		XMLType singleValue = xml;
		XMLType.Type type = null;
		for (Object object : other) {
			if (object != null) {
				if (singleValue != null) {
					type = Type.CONTENT;
					break;
				}
				if (object instanceof XMLType) {
					singleValue = (XMLType)object;
				} else {
					type = Type.CONTENT;
					break;
				}
			}
		}
		if (type == null) {
			return singleValue;
		}
		
		XmlConcat concat = new XmlConcat(context.getBufferManager());
		concat.addValue(xml);
		for (Object object : other) {
			concat.addValue(object);
		}
		return concat.close();
	}
	
	public static class XmlConcat {
		private XMLOutputFactory factory;
		private XMLEventWriter eventWriter;
		private XMLEventFactory eventFactory;
		private Writer writer;
		private FileStoreInputStreamFactory fsisf;
		private FileStore fs;
		private Type type;
		
		public XmlConcat(BufferManager bm) throws TeiidProcessingException {
			fs = bm.createFileStore("xml"); //$NON-NLS-1$
			fsisf = new FileStoreInputStreamFactory(fs, Streamable.ENCODING);
		    writer = fsisf.getWriter();
			factory = getOutputFactory();
			try {
				eventWriter = factory.createXMLEventWriter(writer);
			} catch (XMLStreamException e) {
				fs.remove();
				 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30437, e);
			}
			eventFactory = threadLocalEventtFactory.get();
		}
		
		public void addValue(Object object) throws TeiidProcessingException {
			if (type == null) {
				if (object instanceof XMLType) {
					type = ((XMLType)object).getType();
				}
			} else {
				type = Type.CONTENT;
			}
			try {
				convertValue(writer, eventWriter, eventFactory, object);
			} catch (IOException e) {
				fs.remove();
				 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30438, e);
			} catch (XMLStreamException e) {
				fs.remove();
				 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30439, e);
			} catch (TransformerException e) {
				fs.remove();
				 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30440, e);
			}
		}
		
		public Writer getWriter() {
			return writer;
		}
		
		public XMLType close() throws TeiidProcessingException {
			try {
				eventWriter.flush();
				writer.close();
			} catch (XMLStreamException e) {
				fs.remove();
				 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30441, e);
			} catch (IOException e) {
				fs.remove();
				 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30442, e);
			}
	        XMLType result = new XMLType(new SQLXMLImpl(fsisf));
	        if (type == null) {
	        	result.setType(Type.CONTENT);
	        } else {
	        	result.setType(type);
	        }
	        return result;
		}
		
	}
	
	public static XMLType xmlPi(String name) {
		return xmlPi(name, ""); //$NON-NLS-1$
	}
	
	public static XMLType xmlPi(String name, String content) {
		int start = 0;
		char[] chars = content.toCharArray();
		while (start < chars.length && chars[start] == ' ') {
			start++;
		}
		XMLType result = new XMLType(new SQLXMLImpl("<?" + name + " " + content.substring(start) + "?>")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		result.setType(Type.PI);
		return result;
	}
	
	public static AtomicValue convertToAtomicValue(Object value) throws TransformerException {
		if (value instanceof java.util.Date) { //special handling for time types
        	java.util.Date d = (java.util.Date)value;
        	DateTimeValue tdv = DateTimeValue.fromJavaDate(d);
        	if (value instanceof Date) {
        		value = new DateValue(tdv.getYear(), tdv.getMonth(), tdv.getDay(), tdv.getTimezoneInMinutes());
        	} else if (value instanceof Time) {
        		value = new TimeValue(tdv.getHour(), tdv.getMinute(), tdv.getSecond(), tdv.getMicrosecond(), tdv.getTimezoneInMinutes());
        	} else if (value instanceof Timestamp) {
        		Timestamp ts = (Timestamp)value;
        		value = tdv.add(DayTimeDurationValue.fromMicroseconds(ts.getNanos() / 1000));
        	}
        	return (AtomicValue)value;
        }
		JPConverter converter = JPConverter.allocate(value.getClass(), null);
		return (AtomicValue)converter.convert(value, null);
	}
	
	static void convertValue(Writer writer, XMLEventWriter eventWriter, XMLEventFactory eventFactory, Object object) throws IOException,
			FactoryConfigurationError, XMLStreamException,
			TransformerException {
		if (object == null) {
			return;
		}
		Reader r = null;
		try {
			if (object instanceof XMLType) {
				XMLType xml = (XMLType)object;
				r = xml.getCharacterStream();
				Type type = xml.getType();
				convertReader(writer, eventWriter, r, type);
			} else if (object instanceof Clob) {
				Clob clob = (Clob)object;
				r = clob.getCharacterStream();
				convertReader(writer, eventWriter, r, Type.TEXT);
			} else {
				String val = convertToAtomicValue(object).getStringValue();
				eventWriter.add(eventFactory.createCharacters(val));
			}
		} catch (SQLException e) {
			throw new IOException(e);
		} finally {
			if (r != null) {
				r.close();
			}
		}
		//TODO: blob - with base64 encoding
	}

	private static void convertReader(Writer writer,
			XMLEventWriter eventWriter, Reader r, Type type)
			throws XMLStreamException, IOException, FactoryConfigurationError {
		switch(type) {
		case CONTENT:
		case ELEMENT: 
		case PI:
		case COMMENT: {//write the value directly to the writer
			eventWriter.flush();
			char[] buf = new char[1 << 13];
			int read = -1;
			while ((read = r.read(buf)) != -1) {
				writer.write(buf, 0, read);
			}
			break;
		}
		case UNKNOWN:  //assume a document
		case DOCUMENT: //filter the doc declaration
			XMLInputFactory inputFactory = XMLType.getXmlInputFactory();
			if (!(r instanceof BufferedReader)) {
				r = new BufferedReader(r);
			}
			XMLEventReader eventReader = inputFactory.createXMLEventReader(r);
			eventReader = inputFactory.createFilteredReader(eventReader, new EventFilter() {
				@Override
				public boolean accept(XMLEvent event) {
					return !event.isStartDocument() && !event.isEndDocument();
				}
			});
			eventWriter.add(eventReader);
			break;
		case TEXT:
			XMLEventFactory eventFactory = threadLocalEventtFactory.get();
			char[] buf = new char[1 << 13];
			int read = -1;
			while ((read = r.read(buf)) != -1) {
				eventWriter.add(eventFactory.createCharacters(new String(buf, 0, read)));
			}
			break;
		}
	}
	
	public static XMLType xmlComment(String comment) {
		return new XMLType(new SQLXMLImpl("<!--" + comment + "-->")); //$NON-NLS-1$ //$NON-NLS-2$
	}

    public static Source convertToSource(Object value) throws TeiidProcessingException {
    	if (value == null) {
    		return null;
    	}
    	try {
	    	if (value instanceof SQLXML) {
				return ((SQLXML)value).getSource(null);
	    	}
	    	if (value instanceof Clob) {
	    		return new StreamSource(((Clob)value).getCharacterStream());
	    	}
	    	if (value instanceof Blob) {
	    		return new StreamSource(((Blob)value).getBinaryStream());
	    	}
	    	if (value instanceof String) {
	    		return new StreamSource(new StringReader((String)value));
	    	}
    	} catch (SQLException e) {
			 throw new TeiidProcessingException(QueryPlugin.Event.TEIID30443, e);
		}
    	throw new AssertionError("Unknown type"); //$NON-NLS-1$
    }

    public static String xpathValue(Object doc, String xpath) throws XPathException, TeiidProcessingException {
    	Source s = null;
        try {
        	s = convertToSource(doc);
            XPathEvaluator eval = new XPathEvaluator();
            // Wrap the string() function to force a string return             
            XPathExpression expr = eval.createExpression(xpath);
            Object o = expr.evaluateSingle(s);
            
            if(o == null) {
                return null;
            }
            
            // Return string value of node type
            if(o instanceof Item) {
                return ((Item)o).getStringValue();
            }  
            
            // Return string representation of non-node value
            return o.toString();
        } finally {
        	Util.closeSource(s);
        }
    }
    
    /**
     * Validate whether the XPath is a valid XPath.  If not valid, an XPathExpressionException will be thrown.
     * @param xpath An xpath expression, for example: a/b/c/getText()
     * @throws XPathExpressionException 
     * @throws XPathException 
     */
    public static void validateXpath(String xpath) throws XPathException {
        if(xpath == null) { 
            return;
        }
        
        XPathEvaluator eval = new XPathEvaluator();
        eval.createExpression(xpath);
    }
    
    public static String escapeName(String name, boolean fully) {
    	StringBuilder sb = new StringBuilder();
    	char[] chars = name.toCharArray();
    	int i = 0;
    	if (fully && name.regionMatches(true, 0, "xml", 0, 3)) { //$NON-NLS-1$
			sb.append(escapeChar(name.charAt(0)));
			sb.append(chars, 1, 2);
			i = 3;
    	}
    	for (; i < chars.length; i++) {
    		char chr = chars[i];
    		switch (chr) {
    		case ':':
    			if (fully || i == 0) {
    				sb.append(escapeChar(chr));
    				continue;
    			} 
    			break;
    		case '_':
    			if (chars.length > i && chars[i+1] == 'x') {
    				sb.append(escapeChar(chr));
    				continue;
    			}
    			break;
    		default:
    			//TODO: there should be handling for surrogates
    			//      and invalid chars
    			if (i == 0) {
    				if (!Name11Checker.getInstance().isNCNameStartChar(chr)) {
    					sb.append(escapeChar(chr));
    					continue;
    				}
    			} else if (!Name11Checker.getInstance().isNCNameChar(chr)) {
    				sb.append(escapeChar(chr));
    				continue;
    			}
    			break;
    		}
			sb.append(chr);
		}
    	return sb.toString();
    }

	private static String escapeChar(char chr) {
		CharBuffer cb = CharBuffer.allocate(7);
		cb.append("_u");  //$NON-NLS-1$
		CharsetUtils.toHex(cb, (byte)(chr >> 8));
		CharsetUtils.toHex(cb, (byte)chr);
		return cb.append("_").flip().toString();  //$NON-NLS-1$
	}

    public static SQLXML jsonToXml(CommandContext context, final String rootName, final Blob json) throws TeiidComponentException, TeiidProcessingException, SQLException, IOException {
    	return jsonToXml(context, rootName, json, false);
    }
    
    public static SQLXML jsonToXml(CommandContext context, final String rootName, final Blob json, boolean stream) throws TeiidComponentException, TeiidProcessingException, SQLException, IOException {
		InputStream is = json.getBinaryStream();
		PushbackInputStream pStream = new PushbackInputStream(is, 4);
		byte[] encoding = new byte[3];
		int read = pStream.read(encoding);
		pStream.unread(encoding, 0, read);
		Charset charset = UTF_8;
		if (read > 2) {
			if (encoding[0] == 0) {
				if (encoding[1] == 0) {
					charset = UTF_32BE; 
				} else {
					charset = UTF_16BE;
				}
			} else if (encoding[1] == 0) {
				if (encoding[2] == 0) {
					charset = UTF_32LE; 
				} else {
					charset = UTF_16LE;
				}
			}
		}
		Reader r = new InputStreamReader(pStream, charset);
		return jsonToXml(context, rootName, r, stream);
    }
    
    public static SQLXML jsonToXml(CommandContext context, final String rootName, final Clob json) throws TeiidComponentException, TeiidProcessingException, SQLException {
    	return jsonToXml(context, rootName, json, false);
    }
    
    public static SQLXML jsonToXml(CommandContext context, final String rootName, final Clob json, boolean stream) throws TeiidComponentException, TeiidProcessingException, SQLException {
		return jsonToXml(context, rootName, json.getCharacterStream(), stream);
    }
    
	private static SQLXML jsonToXml(CommandContext context,
			final String rootName, final Reader r, boolean stream) throws TeiidComponentException,
			TeiidProcessingException {
    	JSONParser parser = new JSONParser();
    	final JsonToXmlContentHandler reader = new JsonToXmlContentHandler(rootName, r, parser, threadLocalEventtFactory.get());

		if (stream) {
			//jre 1.7 event logic does not set a dummy location and throws an NPE in StAXSource, so we explicitly set a location
			reader.eventFactory.setLocation(dummyLocation);
			return new SQLXMLImpl() {
				@SuppressWarnings("unchecked")
				public <T extends Source> T getSource(Class<T> sourceClass) throws SQLException {
					if (sourceClass == null || sourceClass == StAXSource.class) {
						StAXSource source;
						try {
							source = new StAXSource(reader);
						} catch (XMLStreamException e) {
							throw TeiidSQLException.create(e);
						}
						return (T) source;
					}
					throw new AssertionError("unsupported source type"); //$NON-NLS-1$
				}
			};
		}
		XMLType result = new XMLType(XMLSystemFunctions.saveToBufferManager(context.getBufferManager(), new XMLTranslator() {
			
			@Override
			public void translate(Writer writer) throws TransformerException,
					IOException {
		    	try {
					XMLOutputFactory factory = getOutputFactory();
					final XMLEventWriter streamWriter = factory.createXMLEventWriter(writer);

			    	streamWriter.add(reader);
					streamWriter.flush(); //woodstox needs a flush rather than a close
				} catch (XMLStreamException e) {
					throw new TransformerException(e);
				} finally {
		    		try {
	    				r.close();
		    		} catch (IOException e) {
		    			
		    		}
		    	}
			}
		}));
		result.setType(Type.DOCUMENT);
		return result;
	}

	/**
	 * This method saves the given XML object to the buffer manager's disk process
	 * Documents less than the maxMemorySize will be held directly in memory
	 */
	public static SQLXMLImpl saveToBufferManager(BufferManager bufferMgr, XMLTranslator translator) 
	    throws TeiidComponentException, TeiidProcessingException {        
	    boolean success = false;
	    final FileStore lobBuffer = bufferMgr.createFileStore("xml"); //$NON-NLS-1$
	    FileStoreInputStreamFactory fsisf = new FileStoreInputStreamFactory(lobBuffer, Streamable.ENCODING);
	    try{  
	    	Writer writer = fsisf.getWriter();
	        translator.translate(writer);
	        writer.close();
	        success = true;
	        return new SQLXMLImpl(fsisf);
	    } catch(IOException e) {
	         throw new TeiidComponentException(QueryPlugin.Event.TEIID30444, e);
	    } catch(TransformerException e) {
	         throw new TeiidProcessingException(QueryPlugin.Event.TEIID30445, e);
	    } finally {
	    	if (!success && lobBuffer != null) {
	    		lobBuffer.remove();
	    	}
	    }
	}
    
}
