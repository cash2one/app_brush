package com.eazy.brush.service.impl;

import com.eazy.brush.dao.entity.Task;
import com.eazy.brush.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author feng.liu
 * @date 2016/8/31 18:03
 */
@Service
public class TaskServiceImpl implements TaskService {

    @Autowired
//    TaskMapper taskMapper;

    @Override
    public Task getById(int id) {
        return null;
    }
}
