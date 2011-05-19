package com.algaworks.jm.reservavoip.sbb;

import jain.protocol.ip.mgcp.JainMgcpEvent;
import jain.protocol.ip.mgcp.message.CreateConnection;
import jain.protocol.ip.mgcp.message.CreateConnectionResponse;
import jain.protocol.ip.mgcp.message.DeleteConnection;
import jain.protocol.ip.mgcp.message.NotificationRequest;
import jain.protocol.ip.mgcp.message.Notify;
import jain.protocol.ip.mgcp.message.NotifyResponse;
import jain.protocol.ip.mgcp.message.parms.CallIdentifier;
import jain.protocol.ip.mgcp.message.parms.ConflictingParameterException;
import jain.protocol.ip.mgcp.message.parms.ConnectionDescriptor;
import jain.protocol.ip.mgcp.message.parms.ConnectionIdentifier;
import jain.protocol.ip.mgcp.message.parms.ConnectionMode;
import jain.protocol.ip.mgcp.message.parms.EndpointIdentifier;
import jain.protocol.ip.mgcp.message.parms.EventName;
import jain.protocol.ip.mgcp.message.parms.NotifiedEntity;
import jain.protocol.ip.mgcp.message.parms.RequestedAction;
import jain.protocol.ip.mgcp.message.parms.RequestedEvent;
import jain.protocol.ip.mgcp.message.parms.ReturnCode;
import jain.protocol.ip.mgcp.pkg.MgcpEvent;
import jain.protocol.ip.mgcp.pkg.PackageName;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.address.AddressFactory;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ContentLengthHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ProxyAuthenticateHeader;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import javax.slee.ActivityContextInterface;
import javax.slee.CreateException;
import javax.slee.RolledBackContext;
import javax.slee.Sbb;
import javax.slee.SbbContext;
import javax.slee.serviceactivity.ServiceStartedEvent;

import net.java.slee.resource.http.events.HttpServletRequestEvent;
import net.java.slee.resource.mgcp.JainMgcpProvider;
import net.java.slee.resource.mgcp.MgcpActivityContextInterfaceFactory;
import net.java.slee.resource.mgcp.MgcpConnectionActivity;
import net.java.slee.resource.mgcp.MgcpEndpointActivity;
import net.java.slee.resource.sip.DialogActivity;
import net.java.slee.resource.sip.SipActivityContextInterfaceFactory;
import net.java.slee.resource.sip.SleeSipProvider;

import org.apache.log4j.Logger;

public abstract class RootSbb implements Sbb {

	private static final Logger logger = Logger.getLogger(RootSbb.class);

	public final static String USUARIO = "12029999";
	private final static String SENHA = "XXXXXXXXX";
	
	public final static String HOST = "registrar.azzu.com.br";
	
	public final static String IVR_ENDPOINT_NAME = "/mobicents/media/IVR/$";
	
	private SbbContext sbbContext;
	
	// JAVAX SIP
	private AddressFactory addressFactory;
	private HeaderFactory headerFactory;
	private MessageFactory messageFactory;

	// RESOURCE SIP
	private SleeSipProvider sleeSipProvider;
	private SipActivityContextInterfaceFactory sipActivityContextInterfaceFactory;
	
	// RESOURCE MGCP
	private JainMgcpProvider mgcpProvider;
	private MgcpActivityContextInterfaceFactory mgcpAcif;
	
	private SbbUtil sbbUtil;
	
	public void onServicoIniciado(ServiceStartedEvent event, ActivityContextInterface aci) {
		logger.debug(">>> Servico Iniciado <<<");
	}
	
	private void sendRequest(Request request, boolean criarDialogo) {
		ClientTransaction clientTransaction = null;
		try {
			clientTransaction = sleeSipProvider.getNewClientTransaction(request);
			
			if (criarDialogo) {
				Dialog dialog = sleeSipProvider.getNewDialog(clientTransaction);
				// dialog.terminateOnBye(true); Não pode colocar este cara, porque ele não receberia o BYE
				sipActivityContextInterfaceFactory.getActivityContextInterface((DialogActivity) dialog).attach(sbbContext.getSbbLocalObject());
				setDialog(dialog);
			}
			
			ActivityContextInterface aciClientTransaction = sipActivityContextInterfaceFactory.getActivityContextInterface(clientTransaction);
			aciClientTransaction.attach(sbbContext.getSbbLocalObject());
		} catch (Exception e) {
			throw new RuntimeException("Erro ao criar dialog/transacao SIP", e);
		}
		
		try {
			clientTransaction.sendRequest();
		} catch (Exception e) {
			throw new RuntimeException("Erro ao tentar enviar a Requisicao SIP", e);
		}
	}
	
	public void onNaoAutorizado(ResponseEvent event, ActivityContextInterface aci) {
		try {
			if (!"REGISTER".equalsIgnoreCase(((CSeqHeader) event.getResponse().getHeader(CSeqHeader.NAME)).getMethod())) {
				ProxyAuthenticateHeader proxyHeader = (ProxyAuthenticateHeader) event.getResponse().getHeader(ProxyAuthenticateHeader.NAME);
				Request requestAutorizado = sbbUtil.criarRequisicaoInviteAutorizado(getTelefone(), getCSeq(), USUARIO, HOST, SENHA, proxyHeader);
				
				sendRequest(requestAutorizado, true);
			} else {
				WWWAuthenticateHeader wwwHeader =  (WWWAuthenticateHeader) event.getResponse().getHeader(WWWAuthenticateHeader.NAME);
				Request registerSIPRequestAuthorization = 
									sbbUtil.criarRequisicaoRegistroAutorizado(
											USUARIO, HOST, SENHA, wwwHeader);
				
				// A resposta 4xx finaliza a transação, portanto preciso criar uma nova
				ClientTransaction clientTransaction = sleeSipProvider.getNewClientTransaction(registerSIPRequestAuthorization);
				
				ActivityContextInterface aciClientTransaction = sipActivityContextInterfaceFactory.getActivityContextInterface(clientTransaction);
				aciClientTransaction.attach(sbbContext.getSbbLocalObject());
				
				clientTransaction.sendRequest();
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}
	
	public void onSolicitarIniciarChamada(HttpServletRequestEvent event, ActivityContextInterface aci) {
		String telefone = event.getRequest().getParameter("telefone");
		String curso = event.getRequest().getParameter("curso");
		
		setTelefone(telefone);
		
		logger.debug("Telefone: " + telefone + ". Curso: " + curso);
		
		setCSeq(sbbUtil.generateCSeq());
		Request request = sbbUtil.criarRequisicaoInvite(telefone, getCSeq());
		
		sendRequest(request, true);
	}
	
	public void on200OK(ResponseEvent event, ActivityContextInterface aci) {
		logger.debug("Respondeu o INVITE");
		
		if (!"BYE".equalsIgnoreCase(((CSeqHeader) event.getResponse().getHeader(CSeqHeader.NAME)).getMethod())) {
			String sdp = new String(event.getResponse().getRawContent());
			criarConexaoComMediaServer(sdp);
		}
	}
	
	private void criarConexaoComMediaServer(String sdp) {
		CallIdentifier callIDMediaServer = mgcpProvider.getUniqueCallIdentifier();
		
		EndpointIdentifier endpointID = new EndpointIdentifier(IVR_ENDPOINT_NAME, getMediaServerIp() + ":" + 2427);

		setCallIdentifier(callIDMediaServer);

		CreateConnection createConnection = new CreateConnection(this, callIDMediaServer, endpointID,
				ConnectionMode.SendRecv);

		try {
			createConnection.setRemoteConnectionDescriptor(new ConnectionDescriptor(sdp));
		} catch (ConflictingParameterException e) {
			throw new RuntimeException("Erro criando conexao com sdp: " + sdp);
		}

		createConnection.setTransactionHandle(mgcpProvider.getUniqueTransactionHandler());
		int txID = createConnection.getTransactionHandle();
		attachToMgcpConnectionActivity(txID, endpointID);

		mgcpProvider.sendMgcpEvents(new JainMgcpEvent[] { createConnection });
	}
	
	public void onCreateConnectionResponse(CreateConnectionResponse event, ActivityContextInterface aci) {
		ReturnCode status = event.getReturnCode();

		switch (status.getValue()) {
		case ReturnCode.TRANSACTION_EXECUTED_NORMALLY:
			logger.debug("Return code: TRANSACTION_EXECUTED_NORMALLY");

			respondAckWithSDP(event);
			MgcpConnectionActivity mgcpConnectionActivity = (MgcpConnectionActivity) aci.getActivity();
			String audioFileUrl = "file:///tmp/audio.wav";
			EndpointIdentifier eid = event.getSpecificEndpointIdentifier();
			MgcpEndpointActivity eActivity = mgcpProvider.getEndpointActivity(eid);
			
			play(eActivity, mgcpConnectionActivity, audioFileUrl);
			break;
		default:
			throw new RuntimeException("############## Nao conseguiu conectar no Media Server ##############");
		}
	}
	
	public void play(MgcpEndpointActivity eActivity, MgcpConnectionActivity connectionActivity, String audioFileUrl) {
		ActivityContextInterface eAci = mgcpAcif.getActivityContextInterface(eActivity);
		eAci.attach(sbbContext.getSbbLocalObject());

		EndpointIdentifier endpointID = eActivity.getEndpointIdentifier();
		ConnectionIdentifier connectionID = new ConnectionIdentifier(connectionActivity.getConnectionIdentifier());

		NotificationRequest notificationRequest = new NotificationRequest(this, endpointID, mgcpProvider
				.getUniqueRequestIdentifier());
		RequestedAction[] actions = new RequestedAction[] { RequestedAction.NotifyImmediately };

		if (audioFileUrl != null) {
			EventName[] signalRequests = null;
			signalRequests = new EventName[] { new EventName(PackageName.Announcement, MgcpEvent.ann.withParm(audioFileUrl), connectionID) };

			notificationRequest.setSignalRequests(signalRequests);

			RequestedEvent[] requestedEvents = {
					new RequestedEvent(new EventName(PackageName.Announcement, MgcpEvent.oc, connectionID), actions),
					new RequestedEvent(new EventName(PackageName.Announcement, MgcpEvent.of, connectionID), actions),
					 };

			notificationRequest.setRequestedEvents(requestedEvents);
		}

		notificationRequest.setTransactionHandle(mgcpProvider.getUniqueTransactionHandler());

		NotifiedEntity notifiedEntity = new NotifiedEntity(getMediaServerIp(), getMediaServerIp(), 2727);
		notificationRequest.setNotifiedEntity(notifiedEntity);

		mgcpProvider.sendMgcpEvents(new JainMgcpEvent[] { notificationRequest });
	}
	
	public void onNotifyRequest(Notify event, ActivityContextInterface aci) {
		 NotifyResponse response = new  NotifyResponse(event.getSource(), ReturnCode.Transaction_Executed_Normally);
			response.setTransactionHandle(event.getTransactionHandle());

			mgcpProvider.sendMgcpEvents(new JainMgcpEvent[] { response });

			EventName[] observedEvents = event.getObservedEvents();

			for (EventName observedEvent : observedEvents) {
				switch (observedEvent.getEventIdentifier().intValue()) {
				case MgcpEvent.REPORT_ON_COMPLETION:
					logger.info("Terminou de tocar o audio");
					
					try {
						Request byeRequest = getDialog().createRequest(Request.BYE);
						ClientTransaction clientTransaction = sleeSipProvider.getNewClientTransaction(byeRequest);
						clientTransaction.sendRequest();
					} catch (SipException e) {
						throw new RuntimeException("Erro ao enviar requisicao BYE", e);
					}
					break;
				}
			}
	}
	
	public void respondAckWithSDP(CreateConnectionResponse event) {
		String sdp = event.getLocalConnectionDescriptor().toString();
		
		ContentTypeHeader contentType = null;
		ContentLengthHeader contentLengthHeader = null;
		try {
			contentType = headerFactory.createContentTypeHeader("application", "sdp");
			contentLengthHeader = headerFactory.createContentLengthHeader(sdp.length());
		} catch (Exception e) {
			throw new RuntimeException("Erro criando content para a resposta do ACK", e);
		}

		Dialog dialog = getDialog();
		try {
			Request request = dialog.createAck(getCSeq());
			request.setContent(sdp, contentType);
			request.addHeader(sbbUtil.getUserAgentHeader());
			request.setContentLength(contentLengthHeader);
			dialog.sendAck(request);
		} catch (Exception e) {
			throw new RuntimeException("Erro enviando ACK com SDP", e);
		}
	}
	
	private void attachToMgcpConnectionActivity(int txID, EndpointIdentifier endpointID) {
		MgcpConnectionActivity connectionActivity = null;
		connectionActivity = mgcpProvider.getConnectionActivity(txID, endpointID);
		ActivityContextInterface epnAci = mgcpAcif.getActivityContextInterface(connectionActivity);
		epnAci.attach(sbbContext.getSbbLocalObject());
	}
	
	public void onCallTerminated(RequestEvent evt, ActivityContextInterface aci) {
		respond(evt, Response.OK);
		releaseState();
	}
	
	private void respond(RequestEvent evt, int cause) {
		try {
			Request request = evt.getRequest();
			ServerTransaction tx = evt.getServerTransaction();
			Response response = messageFactory.createResponse(cause, request);
			tx.sendResponse(response);
		} catch (Exception e) {
			throw new RuntimeException("Erro enviando resposta", e);
		}
	}
	
	private void releaseState() {
		ActivityContextInterface[] activities = sbbContext.getActivities();
		for (ActivityContextInterface activity : activities) {

			if (activity.getActivity() instanceof MgcpConnectionActivity) {
				MgcpConnectionActivity mgcpConnectionActivity = (MgcpConnectionActivity) activity.getActivity();
				deleteMediaServerConnection(mgcpConnectionActivity.getEndpointIdentifier(), mgcpConnectionActivity
						.getConnectionIdentifier());
			}

			activity.detach(sbbContext.getSbbLocalObject());
		}
	}

	private void deleteMediaServerConnection(EndpointIdentifier endpointID, String callIdentifier) {
		DeleteConnection deleteConnection = new DeleteConnection(this, new CallIdentifier(callIdentifier), endpointID);

		deleteConnection.setTransactionHandle(mgcpProvider.getUniqueTransactionHandler());
		mgcpProvider.sendMgcpEvents(new JainMgcpEvent[] { deleteConnection });
	}
	
	@Override
	public void setSbbContext(SbbContext context) {
		this.sbbContext = context;
		
		try {
			Context ctx = (Context) new InitialContext().lookup("java:comp/env");
		
			// Inicializando a API SIP
			sleeSipProvider = (SleeSipProvider) ctx.lookup("slee/resources/jainsip/1.2/provider");

			addressFactory = sleeSipProvider.getAddressFactory();
			headerFactory = sleeSipProvider.getHeaderFactory();
			messageFactory = sleeSipProvider.getMessageFactory();
			sipActivityContextInterfaceFactory = (SipActivityContextInterfaceFactory) ctx.lookup("slee/resources/jainsip/1.2/acifactory");
			
			// Inicializando a api do MGCP para o Media Server
			mgcpProvider = (JainMgcpProvider) ctx.lookup("slee/resources/jainmgcp/2.0/provider/demo");
			mgcpAcif = (MgcpActivityContextInterfaceFactory) ctx.lookup("slee/resources/jainmgcp/2.0/acifactory/demo");
			
		} catch (Exception e) {
			throw new RuntimeException("Não pode iniciar os objetos do RA", e);
		}
		sbbUtil = new SbbUtil(sleeSipProvider, addressFactory, headerFactory, messageFactory);
	}
	
	private String getMediaServerIp() {
		return System.getProperty("jboss.bind.address", "127.0.0.1");
	}
	
	@Override
	public void sbbActivate() {
	}

	@Override
	public void sbbCreate() throws CreateException {
	}

	@Override
	public void sbbExceptionThrown(Exception arg0, Object arg1, ActivityContextInterface arg2) {
	}

	@Override
	public void sbbLoad() {
	}

	@Override
	public void sbbPassivate() {
	}

	@Override
	public void sbbPostCreate() throws CreateException {
	}

	@Override
	public void sbbRemove() {
	}

	@Override
	public void sbbRolledBack(RolledBackContext arg0) {
	}

	@Override
	public void sbbStore() {
	}

	@Override
	public void unsetSbbContext() {
	}
	
	public abstract Dialog getDialog();
	public abstract void setDialog(Dialog dialog);
	
	public abstract long getCSeq();
	public abstract void setCSeq(long cSeq);

	public abstract String getTelefone();
	public abstract void setTelefone(String telefone);
	
	public abstract CallIdentifier getCallIdentifier();
	public abstract void setCallIdentifier(CallIdentifier callIdentifier);
	
	public abstract EndpointIdentifier getPacketRelayEndpointIdentifier();
	public abstract void setPacketRelayEndpointIdentifier(EndpointIdentifier specificEndpointIdentifier);
}
