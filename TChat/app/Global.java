import java.lang.reflect.Method;

import play.Application;
import play.GlobalSettings;
import play.Logger;
import play.mvc.Action;
import play.mvc.Http.Request;


public class Global extends GlobalSettings{

	@Override
	public void onStart(Application app){
		Logger.info("Application has started ..........");
	}
	
	@Override
	public void onStop(Application app){
		Logger.info("Application has shutdown!!!!!!!!!!");
	}
	
	@Override
	public Action onRequest(Request request, Method actionMethod){
		Logger.info("Request: " + request.toString() + " Remote IP: " + request.remoteAddress() + 
				" HTTP method: " + request.method() + " Request URI: " + request.uri());
		return super.onRequest(request, actionMethod);
	}
}
