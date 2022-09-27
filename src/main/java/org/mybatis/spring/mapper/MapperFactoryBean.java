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
package org.mybatis.spring.mapper;

import static org.springframework.util.Assert.notNull;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.support.SqlSessionDaoSupport;
import org.springframework.beans.factory.FactoryBean;

/**
 * BeanFactory that enables injection of MyBatis mapper interfaces. It can be set up with a SqlSessionFactory or a
 * pre-configured SqlSessionTemplate.
 * <p>
 * Sample configuration:
 *
 * <pre class="code">
 * {@code
 *   <bean id="baseMapper" class="org.mybatis.spring.mapper.MapperFactoryBean" abstract="true" lazy-init="true">
 *     <property name="sqlSessionFactory" ref="sqlSessionFactory" />
 *   </bean>
 *
 *   <bean id="oneMapper" parent="baseMapper">
 *     <property name="mapperInterface" value="my.package.MyMapperInterface" />
 *   </bean>
 *
 *   <bean id="anotherMapper" parent="baseMapper">
 *     <property name="mapperInterface" value="my.package.MyAnotherMapperInterface" />
 *   </bean>
 * }
 * </pre>
 * <p>
 * Note that this factory can only inject <em>interfaces</em>, not concrete classes.
 *
 * @author Eduardo Macarron
 *
 * @see SqlSessionTemplate
 */
public class MapperFactoryBean<T> extends SqlSessionDaoSupport implements FactoryBean<T> {

  // mapper接口 Class
  private Class<T> mapperInterface;

  private boolean addToConfig = true;

  public MapperFactoryBean() {
    // intentionally empty
  }

  public MapperFactoryBean(Class<T> mapperInterface) {
    this.mapperInterface = mapperInterface;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void checkDaoConfig() {
    /* 1、验证必须存在sqlSessionTemplate */
    // 验证必须存在sqlSessionTemplate，也就是必须要存在sqlSession。
    // 原因：需要通过sqlSession为接口创建代理类，所以sqlSession不能为空。
    super.checkDaoConfig();

    /* 2、验证必须存在mapper接口 Class */
    // 原因：因为要根据mapper接口创建代理类，没有mapper接口，就不知道为谁创建代理类了
    notNull(this.mapperInterface, "Property 'mapperInterface' is required");

    Configuration configuration = getSqlSession().getConfiguration();

    /* 3、验证是否已经注册了当前接口的mapper，如果没有，就注册当前接口的mapper */
    // 判断Configuration.mapperRegistry.knownMappers中，是否包含当前mapper接口
    if (this.addToConfig && !configuration.hasMapper(this.mapperInterface)) {
      try {
        // ⚠️注册mapper

        // 会判断，必须是接口，才会进行加载和解析
        // 1、先是构建mapper接口对应的MapperProxyFactory，用于生成mapper接口的代理对象；并将它两的对应关系，存入knownMappers（已知mapper）集合中
        // 2、然后去解析mapper，分为2部分
        //（1）先解析mapper接口对应的dao.xml文件，将对应信息放入Configuration；—— 配置文件开发
        //（2）然后再解析mapper接口（把mapper接口作为映射文件进行解析），将对应信息放入Configuration—— 注解开发
        configuration.addMapper(this.mapperInterface);
      } catch (Exception e) {
        logger.error("Error while adding the mapper '" + this.mapperInterface + "' to configuration.", e);
        throw new IllegalArgumentException(e);
      } finally {
        ErrorContext.instance().reset();
      }
    }

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T getObject() throws Exception {
    /**
     * 1、getSqlSession()：获取SqlSessionTemplate
     */
    // 1、先获取SqlSessionTemplate
    // 2、然后通过SqlSessionTemplate，获取mapper接口的代理对象
    // >>> 内部是：(1)从sqlSessionFactory里面获取Configuration；(2)然后通过Configuration，获取mapper接口对于的代理对象
    // >>> >>> 题外：原先是sqlSessionFactory.openSession()获取sqlSession，sqlSession里面留存了Configuration；
    // >>> >>> 后续再通过sqlSession.getMapper()获取接口代理对象时，内部是直接用configuration获取获取接口代理对象，内部没有sqlSessionFactory；
    // >>> >>> 而SqlSessionTemplate不一样了，SqlSessionTemplate保存的是sqlSessionFactory；
    // >>> >>> 在SqlSessionTemplate#getMapper()中，是先从sqlSessionFactory里面获取Configuration，然后通过Configuration，获取mapper接口对于的代理对象
    // SqlSessionTemplate
    return getSqlSession().getMapper(this.mapperInterface);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Class<T> getObjectType() {
    return this.mapperInterface;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSingleton() {
    return true;
  }

  // ------------- mutators --------------

  /**
   * Sets the mapper interface of the MyBatis mapper
   *
   * @param mapperInterface
   *          class of the interface
   */
  public void setMapperInterface(Class<T> mapperInterface) {
    this.mapperInterface = mapperInterface;
  }

  /**
   * Return the mapper interface of the MyBatis mapper
   *
   * @return class of the interface
   */
  public Class<T> getMapperInterface() {
    return mapperInterface;
  }

  /**
   * If addToConfig is false the mapper will not be added to MyBatis. This means it must have been included in
   * mybatis-config.xml.
   * <p>
   * If it is true, the mapper will be added to MyBatis in the case it is not already registered.
   * <p>
   * By default addToConfig is true.
   *
   * @param addToConfig
   *          a flag that whether add mapper to MyBatis or not
   */
  public void setAddToConfig(boolean addToConfig) {
    this.addToConfig = addToConfig;
  }

  /**
   * Return the flag for addition into MyBatis config.
   *
   * @return true if the mapper will be added to MyBatis in the case it is not already registered.
   */
  public boolean isAddToConfig() {
    return addToConfig;
  }
}
