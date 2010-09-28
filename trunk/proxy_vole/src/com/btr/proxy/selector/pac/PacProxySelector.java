package com.btr.proxy.selector.pac;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.btr.proxy.util.Logger;
import com.btr.proxy.util.Logger.LogLevel;
import com.btr.proxy.util.ProxyUtil;


/*****************************************************************************
 * ProxySelector that will use a PAC script to find an proxy for a given URI.
 *
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 ****************************************************************************/
public class PacProxySelector extends ProxySelector {

	private final boolean JAVAX_PARSER = ScriptAvailability.isJavaxScriptingAvailable();

	// private static final String PAC_PROXY = "PROXY";
	private static final String PAC_SOCKS = "SOCKS";
	private static final String PAC_DIRECT = "DIRECT";

	private PacScriptParser pacScriptParser;

	/*************************************************************************
	 * Constructor
	 * @param pacSource the source for the PAC file. 
	 ************************************************************************/

	public PacProxySelector(PacScriptSource pacSource) {
		super();
		selectEngine(pacSource);
	}

	/*************************************************************************
	 * Selects one of the available PAC parser engines.
	 * @param pacSource to use as input.
	 ************************************************************************/
	
	private void selectEngine(PacScriptSource pacSource) {
		try {
			if (this.JAVAX_PARSER) {
				Logger.log(getClass(), LogLevel.INFO,
						"Using javax.script JavaScript engine.");
				this.pacScriptParser = new JavaxPacScriptParser(pacSource);
			} else {
				Logger.log(getClass(), LogLevel.INFO,
						"Using Rhino JavaScript engine.");
				this.pacScriptParser = new RhinoPacScriptParser(pacSource);
			}
		} catch (Exception e) {
			Logger.log(getClass(), LogLevel.ERROR, "PAC parser error.", e);
		}
	}

	/*************************************************************************
	 * connectFailed
	 * @see java.net.ProxySelector#connectFailed(java.net.URI, java.net.SocketAddress, java.io.IOException)
	 ************************************************************************/
	@Override
	public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
		// Not used.
	}

	/*************************************************************************
	 * select
	 * @see java.net.ProxySelector#select(java.net.URI)
	 ************************************************************************/
	@Override
	public List<Proxy> select(URI uri) {
		if (uri == null) {
			throw new IllegalArgumentException("URI must not be null.");
		}

		// Fix for Java 1.6.16 where we get a infinite loop because
		// URL.connect(Proxy.NO_PROXY) does not work as expected.
		PacScriptSource scriptSource = this.pacScriptParser.getScriptSource();
		if (String.valueOf(scriptSource).contains(uri.getHost())) {
			return ProxyUtil.noProxyList();
		}

		return findProxy(uri);
	}

	/*************************************************************************
	 * Evaluation of the given URL with the PAC-file.
	 * 
	 * Two cases can be handled here:
	 * DIRECT 			Fetch the object directly from the content HTTP server denoted by its URL
	 * PROXY name:port 	Fetch the object via the proxy HTTP server at the given location (name and port) 
	 * 
	 * @param uri <code>URI</code> to be evaluated.
	 * @return <code>Proxy</code>-object list as result of the evaluation.
	 ************************************************************************/

	private List<Proxy> findProxy(URI uri) {
		try {
			Set<Proxy> proxies = new HashSet<Proxy>();

			String parseResult = this.pacScriptParser.evaluate(uri.toString(),
					uri.getHost());
			String[] proxyDefinitions = parseResult.split("[;]");
			for (String proxyDef : proxyDefinitions) {
				proxies.add(buildProxyFromPacResult(proxyDef));
			}
			return new ArrayList<Proxy>(proxies);
		} catch (ProxyEvaluationException e) {
			Logger.log(getClass(), LogLevel.ERROR, "PAC resolving error.", e);
			return ProxyUtil.noProxyList();
		}
	}

	/*************************************************************************
	 * The proxy evaluator will return a proxy string. This method will
	 * take this string and build a matching <code>Proxy</code> for it.
	 * @param pacResult the result from the PAC parser.
	 * @return a Proxy
	 ************************************************************************/

	private Proxy buildProxyFromPacResult(String pacResult) {
		if (pacResult == null || pacResult.trim().length() < 6) {
			return Proxy.NO_PROXY;
		}
		if (pacResult.trim().toUpperCase().startsWith(PAC_DIRECT)) {
			return Proxy.NO_PROXY;
		}

		// Check proxy type.
		Proxy.Type type = Proxy.Type.HTTP;
		if (pacResult.trim().toUpperCase().startsWith(PAC_SOCKS)) {
			type = Proxy.Type.SOCKS;
		}

		String host = pacResult.substring(6, pacResult.length());
		Integer port = ProxyUtil.DEFAULT_PROXY_PORT;

		// Split port from host
		String[] token = host.split("[: ]+");
		if (token.length == 2) {
			host = token[0];
			port = Integer.parseInt(token[1]);
		}

		SocketAddress adr = new InetSocketAddress(host, port);
		return new Proxy(type, adr);
	}
}