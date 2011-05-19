package com.algaworks.jm.reservavoip.sbb;

import java.security.MessageDigest;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import javax.sip.InvalidArgumentException;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.URI;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.ProxyAuthenticateHeader;
import javax.sip.header.ProxyAuthorizationHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.UserAgentHeader;
import javax.sip.header.ViaHeader;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;

import net.java.slee.resource.sip.SleeSipProvider;

public class SbbUtil {
	
	private SleeSipProvider sleeSipProvider;
	private AddressFactory addressFactory;
	private HeaderFactory headerFactory;
	private MessageFactory messageFactory;
	
	private UserAgentHeader userAgentHeader;
	
	@SuppressWarnings("unchecked")
	public SbbUtil(SleeSipProvider sipProvider, AddressFactory addressFactory
			, HeaderFactory headerFactory, MessageFactory messageFactory) {
		this.sleeSipProvider = sipProvider;
		this.addressFactory = addressFactory;
		this.headerFactory = headerFactory;
		this.messageFactory = messageFactory;
		
		List userAgents = new ArrayList();
		userAgents.add("AlgaWorks");
		try {
			userAgentHeader = headerFactory.createUserAgentHeader(userAgents);
		} catch (ParseException e) {
			throw new RuntimeException("Erro criando UserAgentHeader", e);
		}
	}

	public static String gerarSenha(String user, String host
			, String password, String realm, String nonce, String method) throws Exception {

		MessageDigest md5 = MessageDigest.getInstance("MD5");

		// ------------ RFC 2069 --------------
		// Generate the response digest according to RFC 2069, i.e.
		// response-digest = <"> < KD ( H(A1), unquoted nonce-value ":" H(A2) > <">
		// A1 = unquoted username-value ":" unquoted realm-value ":" password
		// password = < user's password >
		// A2 = Method ":" digest-uri-value
		// H(A1) = The digested value of A1 converted to a hex string
		// H(A2) = The digested value of A2 converted to a hex string
		// KD = H(A1) ":" nonce ":" H(A2)

		String uri = "sip:" + host;
		
		// Create A1 and A2
		String A1 = user + ":" + realm + ":" + password;
		String A2 = method + ":" + uri.toString();
		// MD5 digest A1
		byte mdbytes[] = md5.digest(A1.getBytes());
		// Create H(A1)
		String HA1 = toHexString(mdbytes);
		// MD5 digest A2
		mdbytes = md5.digest(A2.getBytes());
		// Create H(A2)
		String HA2 = toHexString(mdbytes);
		// Create KD
		String KD = HA1 + ":" + nonce + ":" + HA2;
		mdbytes = md5.digest(KD.getBytes());
		
		String response = toHexString(mdbytes);

		return response;
	}
	
	private static final char[] toHex = { '0', '1', '2', '3', '4', '5', '6', '7', '8',
			'9', 'a', 'b', 'c', 'd', 'e', 'f' };
	
	private static String toHexString(byte b[]) {
		int pos = 0;
		char[] c = new char[b.length * 2];
		for (int i = 0; i < b.length; i++) {
			c[pos++] = toHex[(b[i] >> 4) & 0x0F];
			c[pos++] = toHex[b[i] & 0x0f];
		}
		return new String(c);
	}
	
	public Request criarRequisicaoRegistro(String user, String host) {
		Request registerSIPRequest = null;
		try {
			String localAddress = sleeSipProvider.getListeningPoints()[0].getIPAddress();
			int localPort = sleeSipProvider.getListeningPoints()[0].getPort();
			String localTransport = sleeSipProvider.getListeningPoints()[0].getTransport();
			
			String toURI = "sip:" + user + "@" + host;
			
			URI uri = addressFactory.createURI(toURI);
			String method = Request.REGISTER;
			
			String callId = sleeSipProvider.getNewCallId().getCallId();
			CallIdHeader callIdHeader = headerFactory.createCallIdHeader(callId);
			
			CSeqHeader cSeq = headerFactory.createCSeqHeader(1L, method);
			
			Address fromAddress = addressFactory.createAddress("sip:" + user + "@" + localAddress + ":" + localPort);
			FromHeader from = headerFactory.createFromHeader(fromAddress, "SimpleReferExampleTag_1_1");
			
			Address toAddress = addressFactory.createAddress(toURI);
			ToHeader to = headerFactory.createToHeader(toAddress, null);
			
			ViaHeader viaHeader = headerFactory.createViaHeader(localAddress, localPort, localTransport, null);
			List<ViaHeader> via = new ArrayList<ViaHeader>();
			via.add(viaHeader);
			
			MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70); 
			
			registerSIPRequest = messageFactory.createRequest(
					uri,
					method,
					callIdHeader,
					cSeq,
					from,
					to,
					via,
					maxForwards);
			
			ContactHeader contactHeader = headerFactory.createContactHeader(fromAddress);
			registerSIPRequest.addHeader(contactHeader);
			
		} catch (Exception e) {
			throw new RuntimeException("Erro criando requisição de registro", e);
		}
		return registerSIPRequest;
	}
	
	public Request criarRequisicaoInviteAutorizado(String to, long cSeqNumber, String usuario, String host, String senha
												 , ProxyAuthenticateHeader proxyHeader) {
		
		Request requestAutorizado = criarRequisicaoInvite(to, cSeqNumber);
		String nonce = proxyHeader.getNonce();
		String realm = proxyHeader.getRealm();
		
		try {
			String responseMD5 = gerarSenha(usuario, host, senha, realm, nonce, Request.INVITE);
			ProxyAuthorizationHeader proxyAuthorizationHeader = headerFactory.createProxyAuthorizationHeader("Digest");
			proxyAuthorizationHeader.setUsername(usuario);
			proxyAuthorizationHeader.setAlgorithm("MD5");
			proxyAuthorizationHeader.setRealm(realm);
			proxyAuthorizationHeader.setNonce(nonce);
			proxyAuthorizationHeader.setURI(addressFactory.createURI("sip:" + host));
			proxyAuthorizationHeader.setResponse(responseMD5);
			
			requestAutorizado.setHeader(proxyAuthorizationHeader);
		} catch (Exception e) {
			throw new RuntimeException("Erro gerando autorização", e);
		}
		
		return requestAutorizado;
	}
	
	public Request criarRequisicaoRegistroAutorizado(String user
			, String host, String password, WWWAuthenticateHeader wwwHeader) {
		
		Request registerSIPRequestAuthorization = criarRequisicaoRegistro(user, host);
		
		try {
			String nonce = wwwHeader.getNonce();
			String realm = wwwHeader.getRealm();
			
			String responseMD5 = gerarSenha(user, host, password, realm, nonce, Request.REGISTER);
			
			AuthorizationHeader authorizationHeader = headerFactory.createAuthorizationHeader("Digest");
			authorizationHeader.setUsername(user);
			authorizationHeader.setAlgorithm("MD5");
			authorizationHeader.setRealm(realm);
			authorizationHeader.setNonce(nonce);
			authorizationHeader.setURI(addressFactory.createURI("sip:" + host));
			authorizationHeader.setResponse(responseMD5);
			
			registerSIPRequestAuthorization.setHeader(authorizationHeader);
			
		} catch (Exception e) {
			throw new RuntimeException("Erro gerando autorização", e);
		}
		
		return registerSIPRequestAuthorization;
	}
	
	public Request criarRequisicaoPing(String user, String host) {
		Request optionRequest = null;
		
		try {
			String localAddress = sleeSipProvider.getListeningPoints()[0].getIPAddress();
			int localPort = sleeSipProvider.getListeningPoints()[0].getPort();
			String localTransport = sleeSipProvider.getListeningPoints()[0].getTransport();
			
			String toURI = "sip:ping@" + host;
			
			URI uri = addressFactory.createURI(toURI);
			String method = Request.OPTIONS;
			
			CallIdHeader callIdHeader = sleeSipProvider.getNewCallId();
			
			CSeqHeader cSeq = headerFactory.createCSeqHeader(20L, method);
			
			Address fromAddress = addressFactory.createAddress("sip:" + user + "@" + localAddress + ":" + localPort);
			FromHeader from = headerFactory.createFromHeader(fromAddress, "SimpleReferExampleTag_1_2");
			
			Address toAddress = addressFactory.createAddress(toURI);
			ToHeader to = headerFactory.createToHeader(toAddress, null);
			
			ViaHeader viaHeader = headerFactory.createViaHeader(localAddress, localPort, localTransport, null);
			List<ViaHeader> via = new ArrayList<ViaHeader>();
			via.add(viaHeader);
			
			MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70); 
			
			optionRequest = messageFactory.createRequest(
					uri,
					method,
					callIdHeader,
					cSeq,
					from,
					to,
					via,
					maxForwards);
			
			ContactHeader contactHeader = headerFactory.createContactHeader(fromAddress);
			optionRequest.addHeader(contactHeader);
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		return optionRequest;
	}
	
	public Request criarRequisicaoInvite(String to, long cSeqNumber) {
		Request request = null;

		try {
			CallIdHeader callIdHeader = sleeSipProvider.getNewCallId();
	
			CSeqHeader cSeq = headerFactory.createCSeqHeader(cSeqNumber, Request.INVITE);
			
			String localAddress = sleeSipProvider.getListeningPoints()[0].getIPAddress();
			int localPort = sleeSipProvider.getListeningPoints()[0].getPort();
			String localTransport = sleeSipProvider.getListeningPoints()[0].getTransport();
	
			Address fromAddress = addressFactory.createAddress("sip:" + RootSbb.USUARIO + "@" + localAddress + ":" + localPort);
			String tagFrom = generateTag();
			FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, tagFrom);
	
			String toUriString = "sip:" + to + "@" + RootSbb.HOST;
			URI toURI = addressFactory.createURI(toUriString);
			Address toAddress = addressFactory.createAddress(toURI);
			ToHeader toHeader = headerFactory.createToHeader(toAddress, null);
	
			ViaHeader viaHeader = headerFactory.createViaHeader(localAddress, localPort, localTransport, null);
			List<ViaHeader> via = new ArrayList<ViaHeader>();
			via.add(viaHeader);
	
			MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
	
			request = messageFactory.createRequest(toURI, Request.INVITE, callIdHeader, cSeq, fromHeader, toHeader, via,
												   maxForwards);
	
			ContactHeader contactHeader = headerFactory.createContactHeader(fromAddress);
			request.addHeader(contactHeader);
	
			request.addHeader(userAgentHeader);
		} catch (ParseException e) {
			throw new RuntimeException("Erro de parse criando requisicao SIP", e);
		} catch (InvalidArgumentException e) {
			throw new RuntimeException("Erro de parametro errado criando a requisicao SIP", e);
		}

		return request;
	}
	
	public long generateCSeq() {
		long cSeq = new Long((int) (Math.random() * 100)) + 1L;
		return cSeq;
	}
	
	private String generateTag() {
		return new Integer((int) (Math.random() * 10000)).toString();
	}

	public UserAgentHeader getUserAgentHeader() {
		return userAgentHeader;
	}

}
