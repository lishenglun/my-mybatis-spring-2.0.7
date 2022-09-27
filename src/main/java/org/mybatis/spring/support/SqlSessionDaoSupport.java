/*
 * Copyright 2010-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mybatis.spring.support;

import static org.springframework.util.Assert.notNull;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DaoSupport;

/**
 * Convenient super class for MyBatis SqlSession data access objects. It gives you access to the template which can then
 * be used to execute SQL methods.
 * <p>
 * This class needs a SqlSessionTemplate or a SqlSessionFactory. If both are set the SqlSessionFactory will be ignored.
 * <p>
 *
 * @author Putthiphong Boonphong
 * @author Eduardo Macarron
 *
 * @see #setSqlSessionFactory
 * @see #setSqlSessionTemplate
 * @see SqlSessionTemplate
 */
public abstract class SqlSessionDaoSupport extends DaoSupport {

  // SqlSessionTemplate implements SqlSession
  private SqlSessionTemplate sqlSessionTemplate;

  /**
   * 设置SqlSessionFactory，和初始化SqlSessionTemplate
   *
   * 题外：自动注入的时候，会把该方法当作sqlSessionFactory属性进行注入！所以在进行自动属性注入的时候，会调用该方法！
   *
   * Set MyBatis SqlSessionFactory to be used by this DAO. Will automatically create SqlSessionTemplate for the given
   * SqlSessionFactory.
   *
   * 设置这个DAO使用的MyBatis SqlSessionFactory。
   * 将为给定的SqlSessionFactory，自动创建SqlSessionTemplate。
   *
   * @param sqlSessionFactory
   *          a factory of SqlSession
   */
  public void setSqlSessionFactory(SqlSessionFactory sqlSessionFactory) {
    // sqlSessionFactory != this.sqlSessionTemplate.getSqlSessionFactory()：如果当前存在的sqlSessionTemplate里面的sqlSessionFactory与传入进来的sqlSessionFactory不相等，
    // 则创建一个新的sqlSessionTemplate，新sqlSessionTemplate里面，存放的是传入进来的sqlSessionFactory。
    // 意思就是：使用当前传入进来的sqlSessionFactory，其优先级更高。
    if (this.sqlSessionTemplate == null || sqlSessionFactory != this.sqlSessionTemplate.getSqlSessionFactory()) {
      // 实例化SqlSessionTemplate
      this.sqlSessionTemplate = createSqlSessionTemplate(sqlSessionFactory);
    }
  }

  /**
   * Create a SqlSessionTemplate for the given SqlSessionFactory. Only invoked if populating the DAO with a
   * SqlSessionFactory reference!
   * <p>
   * Can be overridden in subclasses to provide a SqlSessionTemplate instance with different configuration, or a custom
   * SqlSessionTemplate subclass.
   *
   * @param sqlSessionFactory
   *          the MyBatis SqlSessionFactory to create a SqlSessionTemplate for
   * @return the new SqlSessionTemplate instance
   * @see #setSqlSessionFactory
   */
  @SuppressWarnings("WeakerAccess")
  protected SqlSessionTemplate createSqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
    return new SqlSessionTemplate(sqlSessionFactory);
  }

  /**
   * Return the MyBatis SqlSessionFactory used by this DAO.
   *
   * @return a factory of SqlSession
   */
  public final SqlSessionFactory getSqlSessionFactory() {
    return (this.sqlSessionTemplate != null ? this.sqlSessionTemplate.getSqlSessionFactory() : null);
  }

  /**
   * Set the SqlSessionTemplate for this DAO explicitly, as an alternative to specifying a SqlSessionFactory.
   *
   * @param sqlSessionTemplate
   *          a template of SqlSession
   * @see #setSqlSessionFactory
   */
  public void setSqlSessionTemplate(SqlSessionTemplate sqlSessionTemplate) {
    this.sqlSessionTemplate = sqlSessionTemplate;
  }

  /**
   * Users should use this method to get a SqlSession to call its statement methods This is SqlSession is managed by
   * spring. Users should not commit/rollback/close it because it will be automatically done.
   *
   * @return Spring managed thread safe SqlSession
   */
  public SqlSession getSqlSession() {
    return this.sqlSessionTemplate;
  }

  /**
   * Return the SqlSessionTemplate for this DAO, pre-initialized with the SessionFactory or set explicitly.
   * <p>
   * <b>Note: The returned SqlSessionTemplate is a shared instance.</b> You may introspect its configuration, but not
   * modify the configuration (other than from within an {@link #initDao} implementation). Consider creating a custom
   * SqlSessionTemplate instance via {@code new SqlSessionTemplate(getSqlSessionFactory())}, in which case you're
   * allowed to customize the settings on the resulting instance.
   *
   * @return a template of SqlSession
   */
  public SqlSessionTemplate getSqlSessionTemplate() {
    return this.sqlSessionTemplate;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void checkDaoConfig() {
    // 验证必须存在sqlSessionTemplate，也就是必须要存在sqlSession。
    // 原因：需要通过sqlSession为接口创建代理类，所以sqlSession不能为空。
    notNull(this.sqlSessionTemplate, "Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required"/* 需要属性“sqlSessionFactory”或“sqlSessionTemplate” */);
  }

}
