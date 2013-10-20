package controllers;

import java.util.ArrayList;
import java.util.List;

import models.ChatRoom;
import play.Logger;
import play.mvc.Controller;
import play.mvc.Result;

/**
 * This controller class handles all the administration related requests.
 * @author shichaodong
 * @version 1.0
 */
public class Admin extends Controller{
	public static final int ROOMS_PER_PAGE = 6;
	/**
	 * This method returns <i>ROOMS_PER_PAGE</i> chat room details (by default 6)
	 * corresponding to the page number <i>pageLong</i>
	 * @param pageInt Page number.
	 * @return
	 */
	public static Result index(int pageLong){
		ArrayList<ChatRoom> retList = new ArrayList<ChatRoom>();
		List<ChatRoom> members = Application.getChatRooms();
		if(members.size() < 6){
			for(ChatRoom room: members){
				retList.add(room);
			}
		}else{
			for(int i = pageLong * ROOMS_PER_PAGE; i < pageLong * ROOMS_PER_PAGE + ROOMS_PER_PAGE; i ++){
				retList.add(members.get(i));
			}
		}
		Logger.of(Admin.class).info("Admin room size: " + retList.size() + " live chat room list size: " + Application.getChatRooms().size());
		return ok(views.html.admin.render(retList));
	}
}
