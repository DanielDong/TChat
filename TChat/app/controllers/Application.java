package controllers;

import play.mvc.*;
import play.data.*;
import static play.data.Form.*;
import views.html.*;

public class Application extends Controller {


	// -- Actions

    public static Result index() {
        return ok(index.render());
    }

    public static Result startChat(){
    	DynamicForm dyForm = form().bindFromRequest();
    	if(dyForm.hasErrors()){
    		return badRequest(dyForm.errorsAsJson().toString());
    	}else{
    		String userEmailList = dyForm.get("chatTextList");
			String[] userEmailGroup = userEmailList.split(",");
			String ret = "";
			for(int i = 0; i < userEmailGroup.length; i ++){
				ret += (userEmailGroup[i] + "@");
			}
    		return ok(ret);
    	}
    }
  
}
