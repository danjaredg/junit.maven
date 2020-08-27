package org.jarego.junit.maven;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MavenProperties extends Properties {
	public MavenProperties() {
		putAllProperties(System.getProperties());
	}

	public void putAllProperties(Properties prop) {
		for (String key : prop.stringPropertyNames()) {
			setProperty(key, prop.getProperty(key));
		}
	}
	public void putAllProperties(String fileName) throws IOException {
		try (FileInputStream fis = new FileInputStream(fileName)) {
			load(fis);
		}
	}

	@Override
	public synchronized Object put(Object key, Object value) {
		return super.put(key, parse((String)value));
	}

	public String parse(String value) {
		StringBuffer sb = new StringBuffer();
		Pattern p = Pattern.compile("\\$\\{[^\\}]+\\}");
		Matcher m = p.matcher(value);
		while (m.find()) {
			String var = m.group();
			var = var.substring(2, var.length()-1);
			String result = getProperty(var);
			if (result == null)
				result = "${"+var+"}";
			m.appendReplacement(sb, Matcher.quoteReplacement(result));
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
