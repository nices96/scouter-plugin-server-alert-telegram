/*
 *  Copyright 2016 Scouter Project.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); 
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 *  
 *  @author Sang-Cheon Park
 */
package scouter.plugin.server.alert.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import com.google.gson.Gson;

import scouter.lang.AlertLevel;
import scouter.lang.TextTypes;
import scouter.lang.TimeTypeEnum;
import scouter.lang.counters.CounterConstants;
import scouter.lang.pack.AlertPack;
import scouter.lang.pack.MapPack;
import scouter.lang.pack.ObjectPack;
import scouter.lang.pack.PerfCounterPack;
import scouter.lang.pack.XLogPack;
import scouter.lang.plugin.PluginConstants;
import scouter.lang.plugin.annotation.ServerPlugin;
import scouter.net.RequestCmd;
import scouter.server.Configure;
import scouter.server.CounterManager;
import scouter.server.Logger;
import scouter.server.core.AgentManager;
import scouter.server.db.TextRD;
import scouter.server.netio.AgentCall;
import scouter.util.DateUtil;
import scouter.util.HashUtil;

/**
 * Scouter server plugin to send alert via telegram
 * 
 * @author Sang-Cheon Park(nices96@gmail.com) on 2016. 3. 28.
 */
public class TelegramPlugin {
	
	// Get singleton Configure instance from server
    final Configure conf = Configure.getInstance();
    
    private static AtomicInteger ai = new AtomicInteger(0);
    private static List<Integer> javaeeObjHashList = new ArrayList<Integer>();
    private static AlertPack lastPack;
    private static long lastSentTimestamp;
    
    public TelegramPlugin() {
    	if (ai.incrementAndGet() == 1) {
	    	ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
	    	
	    	// thread count check
	    	executor.scheduleAtFixedRate(new Runnable() {
				@Override
				public void run() {
					for (int objHash : javaeeObjHashList) {
						try {
							if (AgentManager.isActive(objHash)) {
								ObjectPack objectPack = AgentManager.getAgent(objHash);
								MapPack mapPack = new MapPack();
				            	mapPack.put("objHash", objHash);
				            	
								mapPack = AgentCall.call(objectPack, RequestCmd.OBJECT_THREAD_LIST, mapPack);
								
				        		int threadCountThreshold = conf.getInt("ext_plugin_thread_count_threshold", 0);
				        		int threadCount = mapPack.getList("name").size();
				        		
				        		if (threadCountThreshold != 0 && threadCount > threadCountThreshold) {
				        			AlertPack ap = new AlertPack();
				        			
				    		        ap.level = AlertLevel.WARN;
				    		        ap.objHash = objHash;
				    		        ap.title = "Thread count exceed a threshold.";
				    		        ap.message = objectPack.objName + "'s Thread count(" + threadCount + ") exceed a threshold.";
				    		        ap.time = System.currentTimeMillis();
				    		        ap.objType = objectPack.objType;
				    				
				    		        alert(ap);
				        		}
							}
						} catch (Exception e) {
							// ignore
						}
					}
				}
	    	}, 
	    	0, 5, TimeUnit.SECONDS);
    	}
	}

    @ServerPlugin(PluginConstants.PLUGIN_SERVER_ALERT)
    public void alert(final AlertPack pack) {
        if (conf.getBoolean("ext_plugin_telegram_send_alert", false)) {
        	
        	// Get log level (0 : INFO, 1 : WARN, 2 : ERROR, 3 : FATAL)
        	int level = conf.getInt("ext_plugin_telegram_level", 0);
        	
        	if (level <= pack.level) {
        		new Thread() {
        			public void run() {
                        try {
                        	// Get server configurations for telegram
                            String token = conf.getValue("ext_plugin_telegram_bot_token");
                            String chatId = conf.getValue("ext_plugin_telegram_chat_id");
                            
                            assert token != null;
                            assert chatId != null;
                        
                            // Make a request URL using telegram bot api
                            String url = "https://api.telegram.org/bot" + token + "/sendMessage";

                        	// Get the agent Name
                        	String name = AgentManager.getAgentName(pack.objHash) == null ? "N/A" : AgentManager.getAgentName(pack.objHash);
                        	
                        	if (name.equals("N/A") && pack.message.endsWith("connected.")) {
                    			int idx = pack.message.indexOf("connected");
                        		if (pack.message.indexOf("reconnected") > -1) {
                        			name = pack.message.substring(0, idx - 6);
                        		} else {
                        			name = pack.message.substring(0, idx - 4);
                        		}
                        	}
                            
                            String title = pack.title;
                            String msg = pack.message;
                            if (title.equals("INACTIVE_OBJECT")) {
                            	title = "An object has been inactivated.";
                            	msg = pack.message.substring(0, pack.message.indexOf("OBJECT") - 1);
                            }
                            
                            try {
	                            String ignoreNamePattern = conf.getValue("ext_plugin_ignore_name_patterns");
	                            String ignoreTitlePattern = conf.getValue("ext_plugin_ignore_title_patterns");
	                            
	                            if (ignoreNamePattern != null && !"".equals(ignoreNamePattern)) {
		                            for (String pattern : ignoreNamePattern.split(",")) {
		                        		if (name.matches(pattern.replaceAll("\\*", ".*"))) {
		                        			return;
		                        		}
		                        	}
	                            }

	                            if (ignoreTitlePattern != null && !"".equals(ignoreTitlePattern)) {
		                            for (String pattern : ignoreTitlePattern.split(",")) {
		                        		if (title.matches(pattern.replaceAll("\\*", ".*"))) {
		                        			return;
		                        		}
		                        	}
	                            }
	                            
	                            if (conf.getBoolean("ext_plugin_ignore_continuous_dup_alert", false) && lastPack != null) {
		                            long diff = System.currentTimeMillis() - lastSentTimestamp;
	                            	if (lastPack.objHash == pack.objHash 
	                            			&& lastPack.title.equals(pack.title)
	                            			&& diff < DateUtil.MILLIS_PER_HOUR) {
	                            		return;
	                            	}
	                            }
	                            
	                            lastPack = pack;
                            } catch (Exception e) {
                            	// ignore
                            	println("[Error] : " + e.getMessage());
                            }
                            
                        	// Make message contents
                            String contents = "[TYPE] : " + pack.objType.toUpperCase() + "\n" + 
                                           	  "[NAME] : " + name + "\n" + 
                                              "[LEVEL] : " + AlertLevel.getName(pack.level) + "\n" +
                                              "[TITLE] : " + title + "\n" + 
                                              "[MESSAGE] : " + msg;
                          
                            Message message = new Message(chatId, contents);
                            String param = new Gson().toJson(message);
                  
                            HttpPost post = new HttpPost(url);
                            post.addHeader("Content-Type","application/json");
                            post.setEntity(new StringEntity(param));
                          
                            CloseableHttpClient client = HttpClientBuilder.create().build();
                          
                            // send the post request
                            HttpResponse response = client.execute(post);
                            
                            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                            	lastSentTimestamp = System.currentTimeMillis();
                                println("Telegram message sent to [" + chatId + "] successfully.");
                            } else {
                                println("Telegram message sent failed. Verify below information.");
                                println("[URL] : " + url);
                                println("[Message] : " + param);
                                println("[Reason] : " + EntityUtils.toString(response.getEntity(), "UTF-8"));
                            }
                        } catch (Exception e) {
                        	println("[Error] : " + e.getMessage());
                        	
                        	if(conf._trace) {
                                e.printStackTrace();
                            }
                        }
        			}
        		}.start();
            }
        }
    }
    
	@ServerPlugin(PluginConstants.PLUGIN_SERVER_OBJECT)
	public void object(ObjectPack pack) {
    	if (pack.version != null && pack.version.length() > 0) {
			AlertPack ap = null;
			ObjectPack op = AgentManager.getAgent(pack.objHash);
	    	
			if (op == null && pack.wakeup == 0L) {
				// in case of new agent connected
				ap = new AlertPack();
		        ap.level = AlertLevel.INFO;
		        ap.objHash = pack.objHash;
		        ap.title = "An object has been activated.";
		        ap.message = pack.objName + " is connected.";
		        ap.time = System.currentTimeMillis();
		        
		        if (AgentManager.getAgent(pack.objHash) != null) {
		        	ap.objType = AgentManager.getAgent(pack.objHash).objType;
		        } else {
		        	ap.objType = "scouter";
		        }
				
		        alert(ap);
	    	} else if (op.alive == false) {
				// in case of agent reconnected
				ap = new AlertPack();
		        ap.level = AlertLevel.INFO;
		        ap.objHash = pack.objHash;
		        ap.title = "An object has been activated.";
		        ap.message = pack.objName + " is reconnected.";
		        ap.time = System.currentTimeMillis();
		        ap.objType = AgentManager.getAgent(pack.objHash).objType;
				
		        alert(ap);
	    	}
			// inactive state can be handled in alert() method.
    	}
	}
    
    @ServerPlugin(PluginConstants.PLUGIN_SERVER_XLOG)
    public void xlog(XLogPack pack) {
    	try {
    		int elapsedThreshold = conf.getInt("ext_plugin_elapsed_time_threshold", 0);
    		
    		if (elapsedThreshold != 0 && pack.elapsed > elapsedThreshold) {
    			String serviceName = TextRD.getString(DateUtil.yyyymmdd(pack.endTime), TextTypes.SERVICE, pack.service);
    			
    			AlertPack ap = new AlertPack();
    			
		        ap.level = AlertLevel.WARN;
		        ap.objHash = pack.objHash;
		        ap.title = "Elapsed time exceed a threshold.";
		        ap.message = "[" + AgentManager.getAgentName(pack.objHash) + "] " 
		        				+ pack.service + "(" + serviceName + ") "
		        				+ "elapsed time(" + pack.elapsed + " ms) exceed a threshold.";
		        ap.time = System.currentTimeMillis();
		        ap.objType = AgentManager.getAgent(pack.objHash).objType;
				
		        alert(ap);
    		}
    		
		} catch (Exception e) {
			Logger.printStackTrace(e);
		}
    }

    @ServerPlugin(PluginConstants.PLUGIN_SERVER_COUNTER)
    public void counter(PerfCounterPack pack) {
        String objName = pack.objName;
        int objHash = HashUtil.hash(objName);
        String objType = null;
        String objFamily = null;

        if (AgentManager.getAgent(objHash) != null) {
        	objType = AgentManager.getAgent(objHash).objType;
        }
        
        if (objType != null) {
        	objFamily = CounterManager.getInstance().getCounterEngine().getObjectType(objType).getFamily().getName();
        }
        
        try {
	        // in case of objFamily is javaee
	        if (CounterConstants.FAMILY_JAVAEE.equals(objFamily)) {
	        	// save javaee type's objHash
	        	if (!javaeeObjHashList.contains(objHash)) {
	        		javaeeObjHashList.add(objHash);
	        	}
	        	
	        	if (pack.timetype == TimeTypeEnum.REALTIME) {
	        		long gcTimeThreshold = conf.getLong("ext_plugin_gc_time_threshold", 0);
	        		long gcTime = pack.data.getLong(CounterConstants.JAVA_GC_TIME);

	        		if (gcTimeThreshold != 0 && gcTime > gcTimeThreshold) {
	        			AlertPack ap = new AlertPack();
	        			
	    		        ap.level = AlertLevel.WARN;
	    		        ap.objHash = objHash;
	    		        ap.title = "GC time exceed a threshold.";
	    		        ap.message = objName + "'s GC time(" + gcTime + " ms) exceed a threshold.";
	    		        ap.time = System.currentTimeMillis();
	    		        ap.objType = objType;
	    				
	    		        alert(ap);
	        		}
	        	}
	    	}
        } catch (Exception e) {
			Logger.printStackTrace(e);
        }
    }

    private void println(Object o) {
        if (conf.getBoolean("ext_plugin_telegram_debug", false)) {
            Logger.println(o);
        }
    }
}