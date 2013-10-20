package controllers;

import static play.data.Form.form;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import models.ChatRoom;
import org.codehaus.jackson.JsonNode;

import play.Logger;
import play.Logger.ALogger;
import play.data.DynamicForm;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.WebSocket;
import utils.MailUtil;
import views.html.index;

public class Application extends Controller {
	
	// Log level: off > fatal > error > warn > info > debug > trace
	private static final ALogger LOG = Logger.of(Application.class);
	
	// Contains all alive chat rooms.
	private static List<ChatRoom> chatRooms = Collections.synchronizedList(new ArrayList<ChatRoom>());
	
	public static synchronized List<ChatRoom> getChatRooms(){return chatRooms;}
	
	// -- Actions
	
	/**
	 * Process request for index page
	 */
    public static Result index() {
        return ok(index.render());
    }

    /**
     * Process request for starting a new chat room.
     * This method does following:
     * <ul>
     * 	<li>sends out chat invitations via e-mail.</li>
     * 	<li>creates a new chat room and store it in the chat room list.</li>
     * </ul>
     * @return HTTP response
     */
    public static Result startChat(){
    	DynamicForm dyForm = form().bindFromRequest();
    	if(dyForm.hasErrors()){
    		return badRequest(dyForm.errorsAsJson().toString());
    	}else{
    		// Get chat room name.
    		String chatRoomName = dyForm.get("chatRoomName");
    		// Get initiator name.
    		String initiatorName = dyForm.get("userName");
    		// Get initiator email address
    		String initiatorMail = dyForm.get("userItem");
    		
    		// chatTextList is a list of email addresses of invited members
    		// including email address of the member who initiates this chat room.
    		String userEmailList = dyForm.get("chatTextList");
			String[] userEmailGroup = userEmailList.split(",");
			
			// Get a new chat room id.
			long newRoomId = generateRoomId();
			
			// Send email to all invited members including initiator.
			String from = initiatorName + " <" + initiatorMail + ">";
			String[] tos = new String[userEmailGroup.length + 1];
			for(int i = 0; i < tos.length; i ++){
				if(i == tos.length - 1){
					tos[i] = initiatorMail;
				}else{
					tos[i] = userEmailGroup[i];
				}
			}
			
			// Create a new chat room
			ChatRoom newChatRoom = new ChatRoom(chatRoomName, newRoomId, tos);
			
			// Add newly created chat room to chat room list
			chatRooms.add(newChatRoom);
			LOG.info("new room added!!!!!!" + chatRooms.get(0).getRoomName());
			
			String subject = "[TChat]" + chatRoomName + " invites you to join their conversation.";
			String body = "Click on the following link to join the room. http://localhost:9000/joinchat?roomId=" + newRoomId + "&username=";
			try{
				for(int i = 0; i < tos.length; i ++){
					// Send chat invitation email to all recipients.
					MailUtil.sendSimpleMail(from, new String[]{tos[i]}, subject, body + tos[i]);
				}
				LOG.debug("email sent successfully.");
			}catch(Exception e){
				LOG.debug(e.getMessage());
			}
			flash("buddyList", userEmailList);
			flash("initiator", initiatorMail);
    		return ok(views.html.chatRoom.render(chatRoomName,initiatorMail, String.valueOf(newRoomId)));
    	}
    }
    
    /**
     * A new member joins in the chat room by click on link in their email 
     * -OR- a group of chat members return to an old chat room.
     * @param username This member's email address.
     * @param roomId The room id.
     * @return
     */
    public static Result joinChat(String username, String roomId){
    	// Get the chat room corresponding to the room id in the live chat room list.
    	ChatRoom targetChatRoom = null;
    	for(int i = 0; i < chatRooms.size(); i ++){
    		if(chatRooms.get(i).getRoomId() == Long.parseLong(roomId)){
    			targetChatRoom = chatRooms.get(i);
    			Logger.of(Application.class).info("joinChat chat room is alive.");
    			break;
    		}
    	}

    	/* Target chat room is not on the live chat room list.
    	 * Check the persisted chat room list.
    	 */
    	if(targetChatRoom == null){
    		// A folder dedicated to this chat room(room id) exists.
    		if(ChatRoom.hasSavedBefore(roomId)){
    			try {
					targetChatRoom = ChatRoom.readPersistedChatRoom(roomId);
					Logger.of(Application.class).info("joinChat chat room is read from disk[persisted read].");
				} catch (IOException e) {
					e.printStackTrace();
					Logger.of(Application.class).info("Read in chat room(" + roomId + ") FAILed -  reading failed.");
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
					Logger.of(Application.class).info("Read in chat room(" + roomId + ") FAILed - reading failed");
				}
    		}
    		// A folder dedicated to this chat room(room id) dose not exist.
    		else{
    			Logger.of(Application.class).info("Read in chat room(" + roomId + ") FAILed - folder does not exist");
    		}
    	}else{
    		Logger.of(Application.class).info("A chat member TRIES to join in a live chat room.");
    	}
    	
    	
    	if(targetChatRoom == null){
    		return badRequest("The chat room you want to join does not exist. :(");
    	}else{
    		// Add this persisted chat room to Appication's chat room list.
    		if(!chatRooms.contains(targetChatRoom)){
    			chatRooms.add(targetChatRoom);
    			Logger.of(Application.class).info("joinChat persisted chat room is made alive by being added to Application.chatRooms.");
    		}
    		String roomName = targetChatRoom.getRoomName();
    		return ok(views.html.chatRoom.render(roomName, username, roomId));
    	}
    }
    
    /**
     * Generate a new chat room id.
     * @return new chat room id.
     */
    private static long generateRoomId(){
    	SecureRandom generator = new SecureRandom();
    	long roomId = 0;
    	do{
    		roomId = generator.nextLong();
    	}while(roomId < 0 || isIdPresent(roomId));
    	return roomId;
    }
    /**
     * Check if a chat room id is already used.
     * @param id Chat room id to be checked.
     * @return true to indicate an already present chat room id; false otherwise.
     */
    private static boolean isIdPresent(long id){
    	for(int i = 0; i < chatRooms.size(); i ++){
    		if(chatRooms.get(i).getRoomId() == id){
    			return true;
    		}
    	}
    	return false;
    }
    
    /**
     * Process request from other invited chat members, request sent via clicking on link in 
     * the email in-box.
     * @param username Chat member's username(email address in this case)
     * @return WebSocket to link the chat member with the chat room.
     */
    public static WebSocket<JsonNode> chat(final String username, final String roomId){
    	LOG.info("Incoming joining member: " + username);
    	return new WebSocket<JsonNode>(){

    		// Called when WebSocket handshake is done.
			@Override
			public void onReady(play.mvc.WebSocket.In<JsonNode> in, play.mvc.WebSocket.Out<JsonNode> out) {
				for(int i = 0; i < chatRooms.size(); i ++){
					if(chatRooms.get(i).getRoomId() == Long.parseLong(roomId)){
						try {
							LOG.info(chatRooms.get(i).join(username, in, out));
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			}
    	};
    }
  
}
