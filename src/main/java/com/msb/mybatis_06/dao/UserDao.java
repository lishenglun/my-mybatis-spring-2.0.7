package com.msb.mybatis_06.dao;

import com.msb.mybatis_06.entity.User;

import java.util.List;

/**
 * @author lishenglun
 * @version v1.0.0
 * @description TODO
 * @date 2022/9/8 10:52 下午
 */
public interface UserDao {

  List<User> getAllUser();

  Integer updateById(Integer id);

}
