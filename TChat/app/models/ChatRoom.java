package models;

import static akka.pattern.Patterns.ask;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
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
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;

public class ChatRoom{
	
	private static final ALogger LOG = Logger.of(ChatRoom.class);
	
	// The literal room name.
	private String chatRoomName;
	// The unique identifier of the chat room.
	private long chatRoomId;
	// The email addresses of room members. (People whose email address not present are not allowed to join room)
	private String[] membersList;
	// An ActorRef pointing to an Actor instance which coordinates the chat information.
	private ActorRef chatRoomActorRef;
	// Recored chat history 
	private Queue<ChatRecord> chatHistory;
	
	public long getRoomId(){return chatRoomId;}
	public String getRoomName(){return chatRoomName;}
	public ActorRef getRoomActorRef(){return chatRoomActorRef;}
	public Queue<ChatRecord> getChatHistoryMap(){return chatHistory;}
	public void setRoomActorRef(ActorRef actorRef){chatRoomActorRef = actorRef;}
	
	public ChatRoom(String roomName, long roomId, String[] memberList){
		chatRoomName = roomName;
		chatRoomId = roomId;
		membersList = memberList;
		chatHistory = new ConcurrentLinkedQueue<ChatRecord>();
		
		for(int i = 0; i < memberList.length; i ++){
			LOG.info("Room member: " + memberList[i]);
		}
		chatRoomActorRef = Akka.system().actorOf(new Props().withCreator(new UntypedActorFactory(){
			/**
			 * 
			 */
			private static final long serialVersionUID = 1L;

			public UntypedActor create() {
			    return new ChatRoomActor();
			}
		}));
		
		
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
					if(kind.equals("text"))
						chatRoomActorRef.tell(new Talk(username, event.get("text").asText()), chatRoomActorRef);
					else if(kind.equals("viewhistory")){
						// view history command is received.
						chatRoomActorRef.tell(new History(username), chatRoomActorRef);
						
					}
				}
			});
			//When the socket is closed.
			in.onClose(new Callback0(){
				@Override
				public void invoke() throws Throwable {
					chatRoomActorRef.tell(new Quit(username), chatRoomActorRef);
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
	
	public class ChatRoomActor extends UntypedActor{
		
		// Store username (email address) and corresponding WebSocket out channel.
		private Map<String, WebSocket.Out<JsonNode>> members = new HashMap<String, WebSocket.Out<JsonNode>>();;

		@SuppressWarnings("deprecation")
		@Override
		public void onReceive(Object msg) throws Exception {
			Date timeTag = new Date();
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss");
			String timeTagStr = sdf.format(timeTag);
			
			boolean isSendHeartBeat = false;
			// A person tries to join this chat room.
			if(msg instanceof Join){
				isSendHeartBeat = true;
				
				Join message = (Join) msg;
				String username = message.getUsername();
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
				
			}
			// A chat member send a message in the chat room.
			else if(msg instanceof Talk){
				isSendHeartBeat = true;
					
				Talk message = (Talk) msg;
				String username = message.getUserName();
				String text = message.getMsg();
				notifyAll("talk", username, text);
				// Add talk chat record to chat history.
				addChatRecordToHistory(timeTagStr, username, text);
			}
			// A member has quit from this chat room.
			else if(msg instanceof Quit){
				isSendHeartBeat = true;
				
				Quit message = (Quit) msg;
				String username = message.getUsername();
				members.remove(username);
				notifyAll("quit", username, " has left this room.");
				// Add quit chat record to chat history.
				addChatRecordToHistory(timeTagStr, username, "has left this room.");
			}
			// Close a chat room which is idle longer than ChatRoomManager.IDLE_MAX milliseconds.
			else if(msg instanceof CloseRoom){
				CloseRoom message = (CloseRoom) msg;
				ActorRef closeRoomActorRef = message.getChatRoomActorRef();
				for(String username: members.keySet()){
					closeRoomActorRef.tell(new Quit(username));
				}
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
				
			}else{
				unhandled(msg);
			}
			// Send heart beat message to ChatRoomManager.
			if(isSendHeartBeat == true){
				ChatRoomManager.sendHeartBeat(new HeartBeat(chatRoomId, chatRoomActorRef), chatRoomActorRef);
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
				event.put("key", "text");
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
	}// end ChatRoomActor
	
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
		
		public Quit(String usrname){
			username = usrname;
		}
		public String getUsername(){return username;}
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
		public CloseRoom(ActorRef roomActorRef){chatRoomActorRef = roomActorRef;}
		public ActorRef getChatRoomActorRef(){return chatRoomActorRef;}
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
		// Corresponding out channel to this user.
//		private WebSocket.Out<JsonNode> out;
		
//		public History(String name, WebSocket.Out<JsonNode> o){
		public History(String name){
			username = name;
//			out = o;
		}
//		public WebSocket.Out<JsonNode> getOutChannel(){return out;}
		public String getUsername(){return username;}
		
	}
	/**
	 * One <i>ChatRecord</i> instance records one chat member's chat record 
	 * @author shichaodong
	 * @version 1.0
	 */
	public static class ChatRecord{
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
