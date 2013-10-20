package models;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Collections;
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
	
   /* The maximum idle time(milliseconds) for each chat room.
	* An chat room whose idle time is longer than this IDLE_MAX value
	* would be closed. i.e., corresponding chat room ActorRef is stopped
	* and chat room instance is removed from the application. (by default 10 minutes)
	*/
	private static final long IDLE_MAX = 10 * 60000;
	
	private static ActorRef chatRoomManager = Akka.system().actorOf(new Props(ChatRoomManager.class), "ChatRoomManager");
	
	public static ActorRef getChatRoomManager(){return chatRoomManager;}
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

	@SuppressWarnings("deprecation")
	@Override
	public void onReceive(Object msg) throws Exception {
		if(msg instanceof Probe){
			for(ChatRoom room: Application.getChatRooms()){
				updateChatRoom(room);
			}
		}
		else{
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
	private void updateChatRoom(ChatRoom chatRoom){
		long curTimeTag = System.currentTimeMillis();
		long lastTimeTag = chatRoom.getTimeTag();
		
		// chatRoom is still eligible to live
		if(curTimeTag - lastTimeTag < IDLE_MAX){
			Logger.of(ChatRoomManager.class).info("Chat Room Manager - chat room(" + chatRoom.getRoomId() + ") TIME updated.[" +
					(IDLE_MAX - curTimeTag + lastTimeTag) / 1000.0 + " seconds left]");
		}
		// chatRoom needs to be removed.
		else{
			// Close this chat room first.
			chatRoom.getRoomActorRef().tell(new CloseRoom(String.valueOf(chatRoom.getRoomId()), chatRoom.getRoomActorRef()));
			Logger.of(ChatRoomManager.class).info("Chat Room Manager remove due chat room[probe]: " + chatRoom.getRoomId());
		}
	}
	
	// -- messages
	public static class Probe{}
}
