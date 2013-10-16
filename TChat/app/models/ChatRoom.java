package models;

import static akka.pattern.Patterns.ask;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
	
	public long getRoomId(){return chatRoomId;}
	public String getRoomName(){return chatRoomName;}
	public ActorRef getRoomActorRef(){return chatRoomActorRef;}
	public void setRoomActorRef(ActorRef actorRef){chatRoomActorRef = actorRef;}
	
	public ChatRoom(String roomName, long roomId, String[] memberList){
		chatRoomName = roomName;
		chatRoomId = roomId;
		membersList = memberList;
		
		for(int i = 0; i < memberList.length; i ++){
			LOG.info("Room member: " + memberList[i]);
		}
//		chatRoomActorRef = Akka.system().actorOf(new Props(ChatRoomActor.class));
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
					chatRoomActorRef.tell(new Talk(username, event.get("text").asText()), chatRoomActorRef);
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
	
	public class ChatRoomActor extends UntypedActor{
		
		// Store username (email address) and corresponding WebSocket out channel.
		private Map<String, WebSocket.Out<JsonNode>> members = new HashMap<String, WebSocket.Out<JsonNode>>();;

		@SuppressWarnings("deprecation")
		@Override
		public void onReceive(Object msg) throws Exception {
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
						notifyAll("join", message.getUsername(), " has joined this room.");
					}
				}
				
			}
			// A chat member send a message in the chat room.
			else if(msg instanceof Talk){
				isSendHeartBeat = true;
					
				Talk message = (Talk) msg;
				notifyAll("talk", message.getUserName(), message.getMsg());
			}
			// A member has quit from this chat room.
			else if(msg instanceof Quit){
				isSendHeartBeat = true;
				
				Quit message = (Quit) msg;
				members.remove(message.getUsername());
				notifyAll("quit", message.getUsername(), " has left this room.");
			}
			// Close a chat room which is idle longer than ChatRoomManager.IDLE_MAX milliseconds.
			else if(msg instanceof CloseRoom){
				CloseRoom message = (CloseRoom) msg;
				ActorRef closeRoomActorRef = message.getChatRoomActorRef();
				for(String username: members.keySet()){
					closeRoomActorRef.tell(new Quit(username));
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
	
}
