/*******************************************************************************
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc, Eolos IT Corp and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 *******************************************************************************/

package org.restcomm.sbc.chain.impl;


import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;

import org.apache.log4j.Logger;
import org.restcomm.chain.ProcessorChain;
import org.restcomm.chain.processor.Message;
import org.restcomm.chain.processor.ProcessorCallBack;
import org.restcomm.sbc.ConfigurationCache;
import org.restcomm.chain.processor.impl.DefaultProcessor;
import org.restcomm.chain.processor.impl.ProcessorParsingException;
import org.restcomm.chain.processor.impl.SIPMutableMessage;
import org.restcomm.sbc.bo.Location;
import org.restcomm.sbc.managers.LocationManager;



/**
 * 
 * @author  Oscar Andres Carriles <ocarriles@eolos.la>
 * @date    25/4/2016 10:16:38
 * @class   RegistrarProcessor.java
 */
/**
 * Specialized Registrar Processor. 
 *
 */
public class RegistrarProcessor extends DefaultProcessor implements ProcessorCallBack {
	
	private static transient Logger LOG = Logger.getLogger(RegistrarProcessor.class);
	private String name="REGISTRAR Processor";
	
	private ProcessorChain chain;
	
	public RegistrarProcessor(ProcessorChain chain) {
		super(chain);
		this.chain=chain;	
	}
	
	public RegistrarProcessor(String name, ProcessorChain chain) {
		this(chain);
		setName(name);
	}
	
/*
	public SipServletMessage process(SipServletMessage message)  {
		if(LOG.isTraceEnabled()){
	          LOG.trace(">> process()");
	     }
		
		if(message instanceof SipServletRequest) {
			message=processRequest((SipServletRequest) message);
		}
		if(message instanceof SipServletResponse) {
			message=processResponse((SipServletResponse) message);
		}
		
		
		return message;
	}
	*/
	/**
	 * Throttling management 
	 * @param dmzRequest
	 * @return message
	 */
	
	private void processRequest(SIPMutableMessage message) {
		
		SipServletRequest dmzRequest=(SipServletRequest) message.getProperty("content");
		
		int expires=dmzRequest.getExpires();
		Address contactAddress = null;
		
		if(ConfigurationCache.isRegThrottleEnabled() && expires !=0) {
			LocationManager locationManager=LocationManager.getLocationManager();
			
			SipURI uri = ((SipURI) dmzRequest.getFrom().getURI());
			String user = uri.getUser();
			String host = uri.getHost();
			int    port = uri.getPort();
	
			int minimum=ConfigurationCache.getRegThrottleMaxUATTL();
			
			if(LOG.isDebugEnabled()){
		          LOG.debug("expires="+expires+" minimum="+minimum);
		    }
			
			if(minimum<expires) {
				if(LOG.isDebugEnabled()){
			          LOG.debug("Registration Throttle for user "+user+" from "+dmzRequest.getExpires()+" to "+minimum);
			    }
				
				expires=minimum;
				
			}
			
			// Deals with DMZ expiration 
			// if DMZ registration is expired
			if(!locationManager.isDmzAlive(user)) {
				// if it does not come from pre-authenticated uri
				if(!locationManager.match(user, host, port)) {
					message.setProperty("content", dmzRequest);
					return;
				}
			}
			
			
			SipServletResponse dmzResponse = dmzRequest.createResponse(200, "Ok");
			locationManager.setDmzExpirationTimeInSeconds(user, expires);
			
			try {
				contactAddress=dmzResponse.getAddressHeader("Contact");
			} catch (ServletParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			dmzResponse.setHeader("Max-Expires", ""+minimum);
			contactAddress.setExpires(minimum);
			dmzResponse.setAddressHeader("Contact", contactAddress);
			
			if(LOG.isDebugEnabled()){
		          LOG.debug(locationManager.getLocation(user));
		    }
			
			// must break chain here and send back to UA
			// after method termination
			
			message.unlink();
			
			message.setProperty("content", dmzRequest);
			
		}
		
		
		message.setProperty("content", dmzRequest);
		
		
		
	}
	
	private void processResponse(SIPMutableMessage message) {
		
		SipServletResponse mzResponse=(SipServletResponse) message.getProperty("content");
		
		Location location;
		
		if(mzResponse.getStatus()== SipServletResponse.SC_OK) {
			LocationManager locationManager=LocationManager.getLocationManager();
			Address address = null;
			SipURI uri = null;
			try {
				address = mzResponse.getRequest().getAddressHeader("Contact");
				uri=(SipURI) address.getURI();
			} catch (ServletParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			String user = ((SipURI) mzResponse.getFrom().getURI()).getUser();
			int mzExpires = mzResponse.getRequest().getExpires();
			int dmzExpires= mzExpires;
			
			if(mzExpires==0) {
				location=locationManager.unregister(user);
				if(LOG.isDebugEnabled()){
			          LOG.debug("UNREGISTER "+location.getUser());
			    }
				
			}	
			else {	
				if(ConfigurationCache.isRegThrottleEnabled()) {
					if(mzExpires>ConfigurationCache.getRegThrottleMaxUATTL()) {
						mzExpires=ConfigurationCache.getRegThrottleMinRegistartTTL();
						dmzExpires=ConfigurationCache.getRegThrottleMaxUATTL();
						
					}
				}
				location=locationManager.getLocation(user);
				
				if(!locationManager.isMzAlive(user)) {
					location=locationManager.register(user, uri.getHost(), uri.getPort(), mzResponse.getRequest().getHeader("User-Agent"), mzResponse.getInitialTransport(), dmzExpires);
					location.setMzExpirationTimeInSeconds(mzExpires);
					location.setDmzExpirationTimeInSeconds(dmzExpires);
					if(LOG.isDebugEnabled()){
				          LOG.debug("REGISTER "+location);
				    }
					
				}
				
			}
		}
		message.setProperty("content", mzResponse);
		
		
	}

	public String getName() {
		return name;
	}

	
	public int getId() {
		return this.hashCode();
	}


	@Override
	public void setName(String name) {
		this.name=name;
		
	}



	@Override
	public ProcessorCallBack getCallback() {
		return this;
	}

	@Override
	public void doProcess(Message message) throws ProcessorParsingException {
		SIPMutableMessage m  =(SIPMutableMessage) message;
		
		SipServletMessage sm = m.getProperty("content");
		
		if(LOG.isTraceEnabled()){
	          LOG.trace(">> doProcess()");
	    }
		
		if(sm instanceof SipServletRequest) {
			processRequest(m);
		}
		if(sm instanceof SipServletResponse) {
			processResponse(m);
		}
		
	}
	
	@Override
	public String getVersion() {
		return "1.0.0";
	}

}
