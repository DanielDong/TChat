package utils;

import java.util.Properties;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.ws.rs.core.MediaType;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.core.util.MultivaluedMapImpl;

import play.Logger;
import play.Logger.ALogger;

public class MailUtil {
	private static final ALogger LOG = Logger.of(MailUtil.class);
	
	/**
	 * This method sends simple mail via HTTP.
	 *  <p>Note: This method uses MailGun(Jersey)</p>
	 * @param from E-mail sender.
	 * @param to   E-mail recipients.
	 * @param subject E-mail subject.
	 * @param body E-mail body.
	 * @return Response from mail server (MailGun)
	 */
	public static ClientResponse sendSimpleMail(String from, String[] to, String subject, String body) throws Exception{
		
	   if(from == null || from == ""){
		   throw new Exception("Please set email sender properly.");
	   }
	   
	   if(to == null || to.length == 0){
		   throw new Exception("Please set email recipients properly.");
	   }
	   
       Client client = Client.create();
       client.addFilter(new HTTPBasicAuthFilter("api",
                       "key-1xigy8-dpzbuy6euywdr2se-6yiu88d3"));
       WebResource webResource =
               client.resource("https://api.mailgun.net/v2/tchat.mailgun.org/messages");
       MultivaluedMapImpl formData = new MultivaluedMapImpl();
       // Set email sender
       formData.add("from", from);
       // Set email recipients.
       for(int i = 0; i < to.length; i ++){
    	   formData.add("to", to[i]);
       }
       // Set email subject
       formData.add("subject", subject);
       // Set email body
       formData.add("text", body);
       return webResource.type(MediaType.APPLICATION_FORM_URLENCODED).post(ClientResponse.class, formData);
	}
	
	/**
	 * Sending email utility function via normal port 25.
	 * <p>Note: This method uses JavaMail</p>
	 * @param from E-mail sender
	 * @param to E-mail recipients
	 * @param host SMTP mail server.
	 * @param subject E-mail subject.
	 * @param content E-mail body
	 * @return
	 * @throws Exception
	 */
	public static boolean sendMail(String from, String[] to, String host, String subject, String content) throws Exception{
		return false;
	}
	/**
	 * Sending email utility function using TLS via port 587.
	 * <p>Note: This method uses JavaMail</p>
	 * @param from E-mail Sender.
	 * @param to E-mail Receiver.
	 * @param host Mail server.
	 * @param subject Email subject.
	 * @param content Email body content.
	 * @return true to indicate successful email delivery; false otherwise.
	 * @throws Exception Exception will be thrown when \"from\", \"to\" or \"host\" is not properly set.
	 */
	public static boolean sendMailTLS(String from, String[] to, String host, String subject, String content) throws Exception{
		if(from == null || from.trim() == ""){
			throw new Exception("Please set \"from\" properly");
		}
		
		if(to == null || to.length == 0){
			throw new Exception("Please set \"to\" properly");
		}
		
		if(host == null || host.trim() == ""){
			throw new Exception("Please set \"host\" properly");
		}
		// User name and Password are needed when using Gmail SMTP mail server.
		// And this practice will ignore the from option set in the message.
		final String username = "dongshichao1988@gmail.com";
		final String pwd = "";
		
		Properties props = System.getProperties();
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.host", host);
		props.put("mail.smtp.port", "587");
		
		
		Session session = Session.getDefaultInstance(props,
			new javax.mail.Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(username, pwd);
			}
		  });
		
		MimeMessage message = new MimeMessage(session);
		
		try {
			// Set From: header field of the heard
			message.setFrom(new InternetAddress("tianyayiren0122@gmail.com"));
			Address[] tos = new InternetAddress[to.length];
			for(int i = 0; i < to.length; i ++){
				tos[i] = new InternetAddress(to[i]);
			}
			// Set To: header field of the header
			message.addRecipients(Message.RecipientType.TO, tos);
			// Set Subject: header field
			message.setSubject(subject);
			// Set email body content
			message.setText(content);
			// Send message
			Transport.send(message);
			
		} catch (AddressException e) {
			e.printStackTrace();
			LOG.debug(e.getMessage());
			return false;
		} catch (MessagingException e) {
			e.printStackTrace();
			LOG.debug(e.getMessage());
			return false;
		}
		// Send successfully
		return true;
	}
}
