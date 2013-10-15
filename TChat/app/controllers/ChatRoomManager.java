package controllers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import play.libs.Akka;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import controllers.ChatRoom.ChatRoomActor;
import controllers.ChatRoom.HeartBeat;
import controllers.ChatRoom.CloseRoom;

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
	
	private Map<Long, ChatRoomUnit> chatRooms = new HashMap<Long, ChatRoomUnit>();
	
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
				long lastTimeTag = roomUnit.getTimeTag();
				long curTimeTag = System.currentTimeMillis();
				// This chat room is not due.
				if(curTimeTag - lastTimeTag < IDLE_MAX){
					roomUnit.setTimeTag(System.currentTimeMillis());
				}
				// This chat room is due and should be closed.
				else{
					// Remove all the chat members from the chat room.
					roomUnit.getChatRoomActorRef().tell(new CloseRoom());
					// Stop due chat room's ActorRef
					Akka.system().stop(roomUnit.getChatRoomActorRef());
					// Remove chat room instance from the chat room list.
					List<ChatRoom> chatRoomList = Application.getChatRooms();
					for(ChatRoom room: chatRoomList){
						if(room.getRoomId() == roomId){
							chatRoomList.remove(room);
							break;
						}
					}
				}
			}
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

}
