package com.kingdee.updateV.service.outer;

import com.alibaba.fastjson.JSON;
import com.kingdee.updateV.constant.Constant;
import com.kingdee.updateV.domain.UpdateVInfo;
import com.kingdee.updateV.dto.ResponseResult;
import com.kingdee.updateV.gateway.ApiMethod;
import com.kingdee.updateV.gateway.ApiModule;
import com.kingdee.updateV.service.UpdateVService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashMap;
import java.util.Map;

/**
 * @author yuanzhen
 */
@ApiModule(service = "version-control", desc = "版本控制服务", outerPath = "/api/version-control/rest/updateV/", innePath = "/version-control/rest/updateV/", protocol = "http", authTypes = {"OAuth2"})
@Controller
@RequestMapping("/rest/updateV/")
public class GatewayOuterController {

    @Autowired
    private UpdateVService updateService;

    final static Logger log = LoggerFactory.getLogger(GatewayOuterController.class);


    @RequestMapping("saveForDesk")
    @ApiMethod(outerPath = "saveForDesk", innerPath = "saveForDesk", desc = "更新", oauth2GrantType = "app", oauth2GroupName = "version-control", oauth2Method = "POST", oauth2Alias = "version-control")
    public ResponseResult saveForDesk(@RequestBody String jsonParam) {
        UpdateVInfo updateV = JSON.parseObject(jsonParam, UpdateVInfo.class);
        log.info("UpdateVInfo:{}", updateV);
        updateV = updateService.updateOrSave(updateV);
        Map<String, String> data = new HashMap<>();
        data.put(Constant.KEY, updateV.getKey());
        return new ResponseResult(true, data);
    }
}
