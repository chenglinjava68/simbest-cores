package com.simbest.cores.web;

import java.util.Date;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.dao.DataAccessException;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.simbest.cores.admin.authority.model.ShiroUser;
import com.simbest.cores.admin.authority.service.ISysUserAdvanceService;
import com.simbest.cores.admin.syslog.model.SysLoginInfo;
import com.simbest.cores.admin.syslog.service.ISysLoginInfoService;
import com.simbest.cores.exceptions.Exceptions;
import com.simbest.cores.exceptions.InvalidateAccountException;
import com.simbest.cores.model.JsonResponse;
import com.simbest.cores.utils.Digests;
import com.simbest.cores.utils.configs.CoreConfig;
import com.simbest.cores.web.filter.SSOAuthenticationToken;

/**
 * 认证方式一：
 * 根据 applicationContext-shiro.xml /login = authc 定义将由Shiro Filter过滤器进行认证，而该过滤器认证自定义ShiroDbRealm实现认证过程
 * Filter认证通过时，该Controller不会执行，直接跳转successUrl的配置页面；
 * 而Filter认证失败时，由Controller继续处理，因此手动再次认证一下从而捕获失败原因，并反馈前端页面！
 * 
 * 认证方式二：
 * 根据 applicationContext-shiro.xml /login = anon，自行在Controller中处理认证情况，以便控制认证成功可以记录登陆日志
 * 
 * @author lishuyi
 */
@Controller
@RequestMapping(value = "/action/login")
public class LoginController {

	public final Log log = LogFactory.getLog(LoginController.class);
	
	@Autowired
	protected CoreConfig coreConfig;
	
	@Autowired
	private ISysUserAdvanceService sysUserAdvanceService;
	
	@Resource(name = "sysLoginInfoService")
	private ISysLoginInfoService sysLoginInfoService;
	
	private Boolean record;
	
	@Autowired
	private void initRecord(CoreConfig coreConfig){
		record = Boolean.valueOf(coreConfig.getValue("app.record.log"));
	}
	
	/**
	 * 显示login页面进行登陆
	 * @return
	 */
	@RequestMapping(method = RequestMethod.GET)
	public String openLoginView() {		
		Subject currentUser = SecurityUtils.getSubject();
		ShiroUser principal = (ShiroUser)currentUser.getPrincipal();
		if(principal == null){
			return "login"; //session超时或重复登陆跳转至view/action/login.jsp
		}
		else{
			return "redirect:/";
		}
	}

	@RequestMapping(value="submit",method = RequestMethod.POST)
	@ResponseBody
	public JsonResponse login(String username, String password) throws Exception {
		UsernamePasswordToken token = new UsernamePasswordToken(username, password.toCharArray());
		JsonResponse res = new JsonResponse();		
		try{
			SecurityUtils.getSubject().login(token);
			res.setResponseid(1);
            saveLoginLog();
		}catch(UnknownAccountException e){
			res.setResponseid(0);
			res.setMessage("用户名不存在！");
		}catch (AuthenticationException e){
			res.setResponseid(0);
			res.setMessage("用户名/密码不对！");
		}catch (InvalidateAccountException e){
			res.setResponseid(0);
			res.setMessage("帐户不可用！");
		}
		return res;
	}
	
	@RequestMapping(value="ssologin",method = RequestMethod.POST)
	@ResponseBody
	public JsonResponse ssologin(String username, String token) throws Exception {
		JsonResponse res = new JsonResponse();	
		String source = username+coreConfig.getCtx();				
		String actual =Digests.encryptMD5(source);
		log.debug("source:"+source);
		log.debug("actual:"+actual);
		if(StringUtils.isEmpty(username)||StringUtils.isEmpty(token)||!token.trim().equals(actual)){
			res.setResponseid(0);
			res.setMessage("单点令牌错误！");
		}else{
			SSOAuthenticationToken ssoToken = new SSOAuthenticationToken(username);
			try{
				SecurityUtils.getSubject().login(ssoToken);
				res.setResponseid(1);
                saveLoginLog();
			}catch(UnknownAccountException e){
				res.setResponseid(0);
				res.setMessage("用户名不存在！");
			}
			catch (AuthenticationException e){
				res.setResponseid(0);
				res.setMessage("用户名/密码错误！");
			}
		}
		return res;
	}
	
	/**
	 * 接收login页面提交登陆用户和密码信息，同时接收Shiro Filter过滤器认证错误返回信息
	 * @param req
	 * @param redirectAttributes
	 * @return
	 */
	@RequestMapping(method = RequestMethod.POST)
	public String onLoginFailed(HttpServletRequest request,RedirectAttributes redirectAttributes) {
		String exceptionClassName = (String)request.getAttribute("shiroLoginFailure");
		 String error = null;
	        if(UnknownAccountException.class.getName().equals(exceptionClassName)) {
	            error = "用户名不存在!";
	        } else if(IncorrectCredentialsException.class.getName().equals(exceptionClassName)) {
	            error = "用户名/密码错误";
	        } else if("jCaptcha.error".equals(exceptionClassName)) {
	            error = "验证码错误";
	        } else if(AuthenticationException.class.getName().equals(exceptionClassName)) {
	            error = "登陆认证失败!";
	        }else if(exceptionClassName != null) {
	            error = "其他错误：" + exceptionClassName;
	        }
	        redirectAttributes.addFlashAttribute("message",error); 
		return "redirect:/action/login"; 
	}

    private void saveLoginLog(){
        if(record){
            Subject currentUser = SecurityUtils.getSubject();
            ShiroUser sysUser = (ShiroUser) currentUser.getPrincipal();
            SysLoginInfo record = new SysLoginInfo();
            record.setSessionid(currentUser.getSession().getId().toString());
            record.setLoginip(currentUser.getSession().getHost());
            record.setLogintime(new Date());
            record.setUseraccount(sysUser.getLoginName());
            record.setUsername(sysUser.getUserName());
            try{
                sysLoginInfoService.insert(record);
            }catch(DataAccessException e){
                log.error(Exceptions.getStackTraceAsString(e));
            }
        }
    }
}