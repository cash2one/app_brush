package com.eazy.brush.controller.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import com.eazy.brush.controller.common.BaseController;
import com.eazy.brush.dao.common.ZQ;
import com.eazy.brush.controller.view.vo.ResultVo;

/**
 * 集成百度地图
 */
@Controller
@RequestMapping("/baidu")
public class BaiDuMapController extends BaseController {

	
	@RequestMapping(value = "list", method = RequestMethod.GET)
	public ModelAndView list() {
		return new ModelAndView("/sys/baidu_log");
	}

	@RequestMapping(value = "getLocations", method = RequestMethod.GET)
	public void getLocations() {
		ResultVo resultVO = new ResultVo(true);

		List<Map<String, Double>> mapList = new ArrayList<>();
		List<Map<String, Object>> result = ZQ.commonResult("select * from login_log");

		for (Map<String, Object> loginLog : result) {
			if (loginLog.get("point_x") != null && loginLog.get("point_y") != null) {
				BigDecimal pointX = new BigDecimal(loginLog.get("point_x").toString());
				BigDecimal pointY = new BigDecimal(loginLog.get("point_y").toString());
				if (pointX != null && pointY != null) {
					Map<String, Double> map = new HashMap<>();
					map.put("x", pointX.doubleValue());
					map.put("y", pointY.doubleValue());
					mapList.add(map);
				}
			}
		}
		resultVO.setData(mapList);
		renderJson(resultVO);
	}
}
