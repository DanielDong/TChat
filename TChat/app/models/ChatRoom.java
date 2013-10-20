package models;

import static akka.pattern.Patterns.ask;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;

import play.Logger;
import play.Logger.ALogger;
import play.libs.Akka;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.libs.Json;
import play.mvc.WebSocket;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import utils.SearchUtil;
import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import controllers.Application;

public class ChatRoom implements Serializable{
	
	/**
	 * 
	 */
	private static final long serialVersionUID = -6504900111383200972L;

	private static final ALogger LOG = Logger.of(ChatRoom.class);
	
	// The literal room name.
	private String chatRoomName;
	// The unique identifier of the chat room.
	private long chatRoomId;
	// The email addresses of room members. (People whose email address not present are not allowed to join room)
	private String[] membersList;
	// An ActorRef pointing to an Actor instance which coordinates the chat information.
	private transient ActorRef chatRoomActorRef;
	// Recored chat history 
	private Queue<ChatRecord> chatHistory;
	// Indicate if chat room is saved or not(false for not saved, true for saved).
	private boolean isSaved = false;
	/* After a chat room is saved, this flag to indicate if updates have been made
	 * since last save.(false for no update, true for new updates have been made)
	 */
	private boolean isUpdated = false;
	
	private long timeTagVal;
	
	public long getRoomId(){return chatRoomId;}
	public String getRoomName(){return chatRoomName;}
	public ActorRef getRoomActorRef(){return chatRoomActorRef;}
	public Queue<ChatRecord> getChatHistoryMap(){return chatHistory;}
	public void setRoomActorRef(ActorRef actorRef){chatRoomActorRef = actorRef;}
	public long getTimeTag(){return timeTagVal;}
	public void setTimeTag(long tag){timeTagVal = tag;}
	
	// For returned chat members
	public ChatRoom(long roomId){chatRoomId = roomId;}
	// For a new chat room.
	public ChatRoom(String roomName, long roomId, String[] memberList){
		chatRoomName = roomName;
		chatRoomId = roomId;
		membersList = memberList;
		Arrays.sort(membersList);
		// The access to chat history is thread-safe.
		chatHistory = new ConcurrentLinkedQueue<ChatRecord>();
		
		for(int i = 0; i < memberList.length; i ++){
			LOG.info("Room member: " + memberList[i]);
		}
		chatRoomActorRef = Akka.system().actorOf(new Props().withCreator(new UntypedActorCreator()));
	}
	
	
	/**
	 * New member joins the chat channel.
	 * @param username A new chat member's username (email address in this case).
	 * @return join action result.
	 * @throws Exception 
	 */
	public String join(final String username, WebSocket.In<JsonNode> in, final WebSocket.Out<JsonNode> out) throws Exception{
		String result = (String)Await.result(ask(chatRoomActorRef, new Join(username, out), 1000), Duration.create(1, SECONDS));
		if(result.equals("BLOCK")){
			return "Unfortunatelly you are not invited to join this chat room." + 
			" But you are welcome to create a new chat room and invite your friends. Enjoy!";
		}else if(result.equals("DISALLOW")){
			return "You have already joinned this chat room and cannot join in without logout.";
		}else if(result.equals("OK")){
			// For each received event on the socket.
			in.onMessage(new Callback<JsonNode>(){
				@Override
				public void invoke(JsonNode event) throws Throwable {
					String kind = event.get("kind").asText();
					// A chat member sends a text message which needs to be broadcast to all other members.
					if(kind.equals("text"))
						chatRoomActorRef.tell(new Talk(username, event.get("text").asText()), chatRoomActorRef);
					// A chat member send a request for viewing the chat history.
					else if(kind.equals("viewhistory")){
						// view history command is received.
						chatRoomActorRef.tell(new History(username), chatRoomActorRef);
						
					}
					// A chat member send a requst to search the chat history for certain text.
					else if(kind.equals("searchchathistory")){
						String searchTxt = event.get("text").asText().trim();
						chatRoomActorRef.tell(new SearchHistory(username, searchTxt), chatRoomActorRef);
					}
					/* A chat member issue the chat save command.
					 * A chat room would be saved when:
					 * 	(1) updates(new messages have been sent by chat members) have been made.
					 *  (2) this is the first time to be saved.
					 */
					else if(kind.equals("savechat")){
						chatRoomActorRef.tell(new SaveChat(username, String.valueOf(chatRoomId)), chatRoomActorRef);
					}
				}
			});
			//When the socket is closed.
			in.onClose(new Callback0(){
				@Override
				public void invoke() throws Throwable {
					chatRoomActorRef.tell(new Quit(username, String.valueOf(chatRoomId)), chatRoomActorRef);
				}
			});
			return "You have successfully joined the chat room. Enjoy!";
		}
		return null;
	}
	
	/**
	 * 
	 * @param timeTag
	 * @param username
	 * @param text
	 */
	private void addChatRecordToHistory(String timeTag, String username, String text){
		chatHistory.add(new ChatRecord(timeTag, username, text));
	}
	/**
	 * 
	 * @return
	 */
	public String getChatHistoryStr(){
		StringBuilder sb = new StringBuilder();
		Iterator<ChatRecord> iter = chatHistory.iterator();
		while(iter.hasNext()){
			ChatRecord cr = iter.next();
			sb.append("<span>" + cr.getUsername() + "		</span><span>" + cr.getTimeTag() + "</span><p>" + cr.getText() + "</p>");
		}
		return sb.toString();
	}
	

	/**
	 * Each chat room has its own folder to store everything.
	 * Folder is named after chat room's room id.
	 * @param roomId Chat room's id.
	 * @return true to indicate this chat room has been saved before(a folder dedicated to this room has been already created); 
	 * false to indicate this chat room is a fresh new one.
	 */
	public static boolean hasSavedBefore(String chatRoomId){
		return new File(String.valueOf(chatRoomId)).exists();
	}
	/**
	 * Create a new chat room folder and store all related resources(image, uploaded files) to this folder.
	 * <ul>Chat room folder structure: 
	 * 	<li>[roomId]                     - chat room folder with chat room id as its name.</li>
	 * 	<ul><li>	[file]                   - file folder to store all uploaded files.</li>
	 * 	<li>	[img]                    - img folder to store all uploaded images.</li>
	 * 	<li>	chatHistory.data         - a file to store persisted chat room(chat room name, chat member list and chat history).</li></ul>
	 * </ul>
	 * @param roomId
	 * @return File object pointing to the chat_history file to extract chat room name, member list and chat history.
	 * @throws IOException 
	 */
	public boolean persistChatRoom(String roomId) throws IOException{
		String fileSeparator = System.getProperty("file.separator");
		File f1 = new File(roomId + fileSeparator + fileSeparator + "file");
		File f2 = new File(chatRoomId + fileSeparator + fileSeparator + "img");
		f1.mkdirs();
		f2.mkdirs();
		
		FileOutputStream fos = new FileOutputStream(roomId + fileSeparator + fileSeparator + "chatRoom.data");
		ObjectOutputStream oos = new ObjectOutputStream(fos);
		oos.writeObject(this);
		oos.flush();
		oos.close();
		return true;
	}
	
	/**
	 * Read in the persisted chat room from disk.
	 * @return the chat room instance.
	 * @throws IOException 
	 * @throws ClassNotFoundException 
	 */
	public static ChatRoom readPersistedChatRoom(String chatRoomId) throws IOException, ClassNotFoundException{
		String fileSeparator = System.getProperty("file.separator");
		File f = new File(chatRoomId + fileSeparator + fileSeparator + "chatRoom.data");
		// File exists and read in the persisted chat room.
		if(f.exists()){
			FileInputStream fis = new FileInputStream(f);
			ObjectInputStream ois = new ObjectInputStream(fis);
			ChatRoom chatRoom = (ChatRoom) ois.readObject();
			
			StringBuilder sb = new StringBuilder();
			sb.append("persisted chat room id: " + chatRoom.getRoomId() + "\n");
			sb.append("persisted chat room name: " + chatRoom.getRoomName() + "\n");
			sb.append("persisted chat room isSaved: " + chatRoom.isSaved + "\n");
			sb.append("persisted chat room isUpdated: " + chatRoom.isUpdated + "\n");
			sb.append("persisted chat room member list size: " + chatRoom.membersList+ "\n");
			sb.append("persisted chat history queue size: " + chatRoom.getChatHistoryMap() + "\n");
			sb.append("persisted chat actor ref: " + chatRoom.getRoomActorRef());
			Logger.of(ChatRoom.class).info(sb.toString());
			
			chatRoom.setRoomActorRef(Akka.system().actorOf(new Props().withCreator(chatRoom.new UntypedActorCreator())));
			
			sb = new StringBuilder();
			sb.append("persisted chat room id: " + chatRoom.getRoomId() + "\n");
			sb.append("persisted chat room name: " + chatRoom.getRoomName() + "\n");
			sb.append("persisted chat room isSaved: " + chatRoom.isSaved + "\n");
			sb.append("persisted chat room isUpdated: " + chatRoom.isUpdated + "\n");
			sb.append("persisted chat room member list size: " + chatRoom.membersList+ "\n");
			sb.append("persisted chat history queue size: " + chatRoom.getChatHistoryMap() + "\n");
			sb.append("persisted chat actor ref: " + chatRoom.getRoomActorRef());
			Logger.of(ChatRoom.class).info(sb.toString());
			
			return chatRoom;
		}
		// File does not exist.
		else{
			return null;
		}
	}
	
	public class ChatRoomActor extends UntypedActor{
		
		// Store username (email address) and corresponding WebSocket out channel.
		private Map<String, WebSocket.Out<JsonNode>> members = new HashMap<String, WebSocket.Out<JsonNode>>();;

		@SuppressWarnings("deprecation")
		@Override
		public void onReceive(Object msg) {
			Date timeTag = new Date();
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss");
			String timeTagStr = sdf.format(timeTag);
			
			// A person tries to join this chat room.
			if(msg instanceof Join){
				StringBuilder sb1 = new StringBuilder();
				sb1.append("right after persisted in join chat room id: " + chatRoomId + "\n");
				sb1.append("right after persisted in join chat room name: " + chatRoomName + "\n");
				sb1.append("right after persisted in join chat room isSaved: " + isSaved + "\n");
				sb1.append("right after persisted in join chat room isUpdated: " + isUpdated + "\n");
				sb1.append("right after persisted in join chat room member list size: " + membersList + "\n");
				sb1.append("right after persisted in join chat history queue size: " + chatHistory + "\n");
				sb1.append("right after persisted in join chat actor ref: " + chatRoomActorRef);
				Logger.of(ChatRoom.class).info(sb1.toString());
				
				
				Join message = (Join) msg;
				String username = message.getUsername();
				
				StringBuilder sb = new StringBuilder();
				for(String str: membersList){
					sb.append(str + " ");
				}
				
				Logger.of(ChatRoomActor.class).info("member list of this chat room: " + sb.toString() + " joining username: " + username);
				// Insertion point of this username in the membersList list.
				int index = Arrays.binarySearch(membersList, username);
				// This username does not occur in the membersList and 
				// should be BLOCKed from joining in this chat room.
				if(index < 0){
					getSender().tell("BLOCK");
				}else{
					// Check if this member has already connected to the chat room.
					// This member has already joined the chat room and SHOULD NOT join again!
					if(members.containsKey(username)){
						getSender().tell("DISALLOW");
					}
					// This member is on the invitation list and ALLOWed to join. This is the first time 
					// for this member to join this room.
					else{
						getSender().tell("OK");
						members.put(username, message.out);
						notifyAll("join", username, " has joined this room.");
						// Add join chat record to chat history.
						addChatRecordToHistory(timeTagStr, username, "has joined this room.");
					}
				}
				timeTagVal = System.currentTimeMillis();
				isUpdated = true;
			}
			// A chat member send a message in the chat room.
			else if(msg instanceof Talk){
					
				Talk message = (Talk) msg;
				String username = message.getUserName();
				String text = message.getMsg();
				notifyAll("talk", username, text);
				// Add talk chat record to chat history.
				addChatRecordToHistory(timeTagStr, username, text);
				isUpdated = true;
				timeTagVal = System.currentTimeMillis();
			}
			// A member has quit from this chat room.
			else if(msg instanceof Quit){
				
				Quit message = (Quit) msg;
				String username = message.getUsername();
				String roomId = message.getRoomId();
				members.remove(username);
				notifyAll("quit", username, " has left this room.");
				// Add quit chat record to chat history.
				addChatRecordToHistory(timeTagStr, username, "has left this room.");
				isUpdated = true;
				Logger.of(ChatRoomActor.class).info("ChatRoomActor members size: " + members.size());
				// All chat members have left the chat room.
				if(members.size() == 0){
					chatRoomActorRef.tell(new CloseRoom(roomId, chatRoomActorRef), chatRoomActorRef);
				}
				timeTagVal = System.currentTimeMillis();
			}
			// Close a chat room which is idle longer than ChatRoomManager.IDLE_MAX milliseconds.
			else if(msg instanceof CloseRoom){
				CloseRoom message = (CloseRoom) msg;
				String roomId = message.getRoomId();
				ActorRef closeRoomActorRef = message.getChatRoomActorRef();
				if(members.size() > 0){
					for(String username: members.keySet()){
						closeRoomActorRef.tell(new Quit(username, roomId));
					}
				}
				
				// Close the ActorRef of this chat room.
				if(closeRoomActorRef != null){
					Akka.system().stop(message.getChatRoomActorRef());
					chatRoomActorRef = null;
					Logger.of(ChatRoomActor.class).info("Chat room actor ref is set to NULL......" + chatRoomActorRef);
				}
				// Persist this chat room.
				try{
					persistChatRoom(roomId);
					Logger.of(ChatRoomActor.class).info("Chat room persisted on CloseRoom command......");
				}catch(Exception e){
					Logger.of(ChatRoomActor.class).info("persistChatRoom FAILed in CloseRoom: " + e.getMessage());
				}
				// Remove this chat room(list unchanged if this chat room has been removed)
				Iterator<ChatRoom> iter = Application.getChatRooms().iterator();
				while(iter.hasNext()){
					ChatRoom room = iter.next();
					if(room.getRoomId() == chatRoomId){
						iter.remove();
						break;
					}
				}
				
				Logger.of(ChatRoomActor.class).info("Chat room is removed from live chat room list......live chat room size: " + Application.getChatRooms().size());
			}
			// A chat member ask to see the chat history.
			else if(msg instanceof History){
				History message = (History) msg;
				WebSocket.Out<JsonNode> channel = members.get(message.getUsername());
				if(channel != null){
					ObjectNode event = Json.newObject();
					event.put("key", "history");
					event.put("text", getChatHistoryStr());
					channel.write(event);
					
				}
				// The channel that belongs to this chat member is lost.
				else{
					Logger.of(ChatRoomActor.class).info(message.getUsername() +  "'s channel socket is lost.");
				}
				timeTagVal = System.currentTimeMillis();
			}
			// Process a chat member's search chat history request.
			else if(msg instanceof SearchHistory){
				SearchHistory message = (SearchHistory) msg;
				ArrayList<String> retList = SearchUtil.searchChatHistory(getChatHistoryStr(), message.getSearchTxt());
				String historyMsg = retList.get(0);
				String numOfMatches = retList.get(1);
				
				WebSocket.Out<JsonNode> channel = members.get(message.getUsername());
				if(channel != null){
					ObjectNode event = Json.newObject();
					// key indicates the type of message.
					event.put("key", "searchhistory");
					// kind indicates the CSS class of message.
					event.put("text", historyMsg);
					event.put("numofmatch", numOfMatches);
					channel.write(event);
				}
				timeTagVal = System.currentTimeMillis();
			}else if(msg instanceof SaveChat){
				SaveChat message = (SaveChat) msg;
				String username = message.getUsername();
				String roomId = message.getRoomId();
				// This chat room history has been saved early in this chat session.
				if(isSaved == true){
					// New updates have been made since last save.
					if(isUpdated == true){
						try{
							persistChatRoom(roomId);
						}catch(Exception e){
							Logger.of(ChatRoomActor.class).info("persistChatRoom FAILed in SaveChat(isSaved - isUpdated both true): " + e.getMessage());
						}
						// Command: chatsaved to indicate chat room has been saved.
						notifyCertainUser("chatsaved", "text", username, "Chat has been saved successuflly!");
						
					}
					// NO updates made since last save.
					else{
						notifyCertainUser("chatsaved", "text", username, "NO updates made since last save.");
					}
				}
				/* This is the first time to save this chat room history
				 * in this chat session.
				 */
				else{
					boolean isSaveSuccessful = false;

					try{
						isSaveSuccessful = persistChatRoom(roomId);
					}catch(Exception e){
						Logger.of(ChatRoomActor.class).info("persistChatRoom FAILed(folder does not exist): " + e.getMessage());
					}
					
					if(isSaveSuccessful == true){
						notifyCertainUser("chatsaved", "text", username, "Chat has been saved successuflly!");
					}else{
						notifyCertainUser("chatsaved", "text", username, "NO updates made since last save.");
					}
					
				}
				isUpdated = false;
				timeTagVal = System.currentTimeMillis();
				Logger.of(ChatRoomActor.class).info("isSaved: " + isSaved + " isUpdated: " + isUpdated);
			}else{
				unhandled(msg);
			}
		}
		
		/**
		 * Broadcast message to all alive chat members.
		 * @param kind Indicate the type of message(Join, Talk, Quit)
		 * @param username A chat member's email address.
		 * @param msg Message to be sent to all alive chat members.
		 */
		public void notifyAll(String kind, String username, String msg){
			for(WebSocket.Out<JsonNode> out: members.values()){
				ObjectNode event = Json.newObject();
				// key indicates the type of message.
				event.put("key", "text");
				// kind indicates the CSS class of message.
				event.put("kind", kind);
				// Joining or talking member's email address.
				event.put("username", username);
				event.put("text", msg);
				// members' email address list.
				ArrayNode nameList = event.putArray("members");
				for(String name: members.keySet()){
					nameList.add(name);
				}
				out.write(event);
			}
		}
		/**
		 * Send a message to a certian chat member.
		 * @param kind Message type.
		 * @param username Chat member's email address.
		 * @param msg Message.
		 */
		public void notifyCertainUser(String key, String kind, String username, String msg){
			WebSocket.Out<JsonNode> out = members.get(username);
			ObjectNode event = Json.newObject();
			event.put("key", key);
			event.put("kind", kind);
			event.put("username", username);
			event.put("text", msg);
			out.write(event);
		}
	}// end ChatRoomActor
	
	private class UntypedActorCreator implements UntypedActorFactory{
		/**
		 * 
		 */
		private static final long serialVersionUID = 4932693100022213825L;
		
		@Override
		public Actor create() throws Exception {
			return new ChatRoomActor();
		}
	}
	
	// -- messages
	
	/**
	 * Each room member joins chat room by sending a <i>Join</i> message to 
	 * the chat room on the server.
	 */
	public static class Join{
		// Member's email address.
		private final String username;
		private final WebSocket.Out<JsonNode> out;
		
		public Join(String usrname, WebSocket.Out<JsonNode> outChannel){
			username = usrname;
			out = outChannel;
		}
		public String getUsername(){return username;}
		public WebSocket.Out<JsonNode> getOutChannel(){return out;}
	}
	
	/**
	 * Each event sent by client via WebSocket is a <i>Talk</i> message which
	 * will be dispatched to all alive chat room members.
	 */
	public static class Talk{
		// Member's email address.
		private final String username;
		private final String msg;
		
		public Talk(String usrname, String message){
			username = usrname;
			msg = message;
		}
		public String getUserName(){return username;}
		public String getMsg(){return msg;}
	}
	
	/**
	 * A <i>Quit</i> message will be sent to the chat room 
	 * when a chat member leaves either intentionally or accidentally
	 */
	public static class Quit{
		// Member's email address.
		private final String username;
		// room id
		private String roomId;
		
		public Quit(String usrname, String id){
			username = usrname;
			roomId = id;
		}
		public String getUsername(){return username;}
		public String getRoomId(){return roomId;}
	}
	/**
	 * An HeartBeat message is sent to the ChatRoomManager periodically by each 
	 * chat room instance to indicate their liveness.
	 */
	public static class HeartBeat{
		private long roomId;
		private ActorRef chatRoomActorRef;
		
		public HeartBeat(long id, ActorRef actorRef){
			roomId = id;
			chatRoomActorRef = actorRef;
		}
		
		public long getRoomId(){return roomId;}
		public ActorRef getChatRoomActorRef(){return chatRoomActorRef;}
	}
	
	public static class CloseRoom{
		private ActorRef chatRoomActorRef;
		private String roomId;
		
		public CloseRoom(String id, ActorRef roomActorRef){
			roomId = id;
			chatRoomActorRef = roomActorRef;
		}
		public ActorRef getChatRoomActorRef(){return chatRoomActorRef;}
		public String getRoomId(){return roomId;}
	}
	/**
	 * A <i>History</i> message is sent by client to view the chat history
	 * of the received chat room.
	 * @author shichaodong
	 * @version 1.0
	 */
	public static class History{
		// Chat member who issues this view history command
		private String username;
		public History(String name){
			username = name;
		}
		public String getUsername(){return username;}
		
	}
	/**
	 * A chat member sends a <i>SearchHistory</i> message to search through 
	 * the chat room's chat history with search text specified.
	 * @author shichaodong
	 * @version 1.0
	 */
	public static class SearchHistory{
		private String username;
		private String searchTxt;
		public SearchHistory(String name, String txt){
			username = name;
			searchTxt = txt;
		}
		public String getUsername(){
			return username;
		}
		
		public String getSearchTxt(){
			return searchTxt;
		}
	}
	/**
	 * A <i>SaveChat</i> message is sent by a chat member to save
	 * by a current chat history. 
	 * @author shichaodong
	 * @version 1.0
	 */
	public static class SaveChat{
		private String username;
		private String roomId;
		public SaveChat(String name, String id){
			username = name;
			roomId = id;
		}
		public String getRoomId(){return roomId;}
		public String getUsername(){return username;}
	}
	
	/**
	 * One <i>ChatRecord</i> instance records one chat member's chat record 
	 * @author shichaodong
	 * @version 1.0
	 */
	public static class ChatRecord implements Serializable{
		/**
		 * 
		 */
		private static final long serialVersionUID = 8379865466312546408L;
		//Time tag of this record
		private String timeTag;
		// Chat member's email address
		private String username;
		// Chat member's chat message.
		private String text;
		public ChatRecord(String time, String name, String msg){
			timeTag = time;
			username = name;
			text = msg;
		}
		public String getTimeTag(){return timeTag;}
		public String getUsername(){return username;}
		public String getText(){return text;}
	}
	
}
