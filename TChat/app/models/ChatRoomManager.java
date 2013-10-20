package models;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import models.ChatRoom.ChatRoomActor;
import models.ChatRoom.CloseRoom;
import models.ChatRoom.HeartBeat;
import play.Logger;
import play.libs.Akka;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import controllers.Application;
/**
 * This ChatRoomManager Actor is created when the web site is started.
 * Only one such Actor instance exists during the whole web site life cycle.
 * It manages all the chat room actors {@link ChatRoomActor}.
 * 
 * @author shichaodong
 * @version 1.0
 */
public class ChatRoomManager extends UntypedActor{
	
	// The maximum idle time(milliseconds) for each chat room.
	// An chat room whose idle time is longer than this IDLE_MAX value
	// would be closed. i.e., corresponding chat room ActorRef is stopped
	// and chat room instance is removed from the application. (by default 10 minutes)
	private static final long IDLE_MAX = 10 * 60000;
	
	private static ActorRef chatRoomManager = Akka.system().actorOf(new Props(ChatRoomManager.class), "ChatRoomManager");
	
	public static ActorRef getChatRoomManager(){return chatRoomManager;}
	// key is room id, value is ChatRoomUnit instance
	private Map<Long, ChatRoomUnit> chatRooms = new HashMap<Long, ChatRoomUnit>();
	
	/**
	 * Start the periodic probe message sending process.
	 */
	public static void init(){
		// Send probe message to ChatRoomManager every 60 seconds.
		Akka.system().scheduler().schedule(
	            Duration.create(60, SECONDS),
	            Duration.create(60, SECONDS),
	            chatRoomManager,
	            new Probe(),
	            Akka.system().dispatcher()
	        );
	}
	
	/**
	 * 
	 * @param heartBeatMsg
	 * @param chatRoomActorRef
	 */
	public static void sendHeartBeat(HeartBeat heartBeatMsg, ActorRef chatRoomActorRef){
		chatRoomManager.tell(heartBeatMsg, chatRoomActorRef);
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onReceive(Object msg) throws Exception {
		if(msg instanceof HeartBeat){
			HeartBeat message = (HeartBeat) msg;
			long roomId = message.getRoomId();
			ActorRef chatRoomActorRef = message.getChatRoomActorRef();
			// A new chat room has been created.
			if(chatRooms.keySet().contains(roomId) == false){
				chatRooms.put(roomId, new ChatRoomUnit(chatRoomActorRef, System.currentTimeMillis()));
			}
			// An already alive chat room sends this heart beat message
			else{
				ChatRoomUnit roomUnit = chatRooms.get(roomId);
				updateEachChatRoom(roomId, roomUnit, "heartbeat");
			}
			Logger.of(ChatRoomManager.class).info("Chat Room Manager - HeartBeat message received: " + message.getRoomId());
		}else if(msg instanceof Probe){
			Set<Long> roomIdSet = chatRooms.keySet();
			Iterator<Long> iter = roomIdSet.iterator();
			while(iter.hasNext()){
				long roomId = iter.next();
				ChatRoomUnit roomUnit = chatRooms.get(roomId);
				updateEachChatRoom(roomId, roomUnit, "probe");
			}
			
		}else{
			unhandled(msg);
		}
	}
	/**
	 * This method either updates the chat room unit instance's time tag (if this chat room is not due) or
	 * close the chat room (if this chat room is due).
	 * @param roomId Chat room id.
	 * @param roomUnit Chat room unit instance.
	 */
	@SuppressWarnings("deprecation")
	private void updateEachChatRoom(long roomId, ChatRoomUnit roomUnit, String msgType){
		long lastTimeTag = roomUnit.getTimeTag();
		long curTimeTag = System.currentTimeMillis();
		// This chat room is not due.
		if(curTimeTag - lastTimeTag < IDLE_MAX){
			if(msgType.equals("probe")){
				Logger.of(ChatRoomManager.class).info("Chat Room Manager - chat room(" + roomId + ") TIME updated.[" +
						(IDLE_MAX - curTimeTag + lastTimeTag) / 1000.0 + " seconds left]");
			}else if(msgType.equals("heartbeat")){
				roomUnit.setTimeTag(System.currentTimeMillis());
				Logger.of(ChatRoomManager.class).info("Chat Room Manager - chat room(" + roomId + ") TIME updated.[NEW]");
			}
			
		}
		// This chat room is due and should be closed.
		else{
			// Remove all the chat members from the chat room.
			roomUnit.getChatRoomActorRef().tell(new CloseRoom(String.valueOf(roomId), roomUnit.getChatRoomActorRef()));
			// Stop due chat room's ActorRef
			Akka.system().stop(roomUnit.getChatRoomActorRef());
			// Remove chat room instance from the chat room list.
			List<ChatRoom> chatRoomList = Application.getChatRooms();
			for(ChatRoom room: chatRoomList){
				if(room.getRoomId() == roomId){
					chatRoomList.remove(room);
					chatRooms.remove(roomId);
					Logger.of(ChatRoomManager.class).info("Chat Room Manager CLOSEes chat rooms: " + roomId);
					break;
				}
			}
			Logger.of(ChatRoomManager.class).info("Chat Room Manager TRIES TO CLOSE chat rooms: " + roomId);
		}
	}
	
	public static class ChatRoomUnit{
		private ActorRef chatRoomActorRef;
		private long timeTag;
		
		public ChatRoomUnit(ActorRef roomActorRef, long timetag){
			chatRoomActorRef = roomActorRef;
			timeTag = timetag;
		}
		
		public ActorRef getChatRoomActorRef(){return chatRoomActorRef;}
		public long getTimeTag(){return timeTag;}
		public void setTimeTag(long newTimetag){
			timeTag = newTimetag;
		}
	}
	
	// -- messages
	public static class Probe{}

}
