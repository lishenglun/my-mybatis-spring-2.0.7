package com.msb.mybatis_06.service;

import com.msb.mybatis_06.dao.AccountDao;
import com.msb.mybatis_06.dao.UserDao;
import com.msb.mybatis_06.entity.Account;
import com.msb.mybatis_06.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author lishenglun
 * @version v1.0.0
 * @description
 * @date 2022/9/9 4:20 下午
 */
@Service
public class UserServiceImpl implements UserService {

  @Autowired
  private UserDao userDao;

  @Autowired
  private AccountDao accountDao;

  //@Transactional
  @Override
  public List<User> getAllUser() {
    //userDao.updateById(1);

    List<User> allUser = userDao.getAllUser();
    System.out.println(allUser);

    List<Account> allAccount = accountDao.getAllAccount();
    System.out.println(allAccount);

    return allUser;
  }

}
