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

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Optional;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyResourceConfigurer;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * 扫描mapper接口，注册对应的bd到spring容器中
 *
 * 题外：当前类，有意义的代码是：MapperScannerConfigurer#postProcessBeanDefinitionRegistry()，像postProcessBeanFactory()是空实现，
 * afterPropertiesSet()是校验必须要配置basePackage、setApplicationContext()、setBeanName()都是正常的设置值而已！
 *
 * 题外：如果我们需要用到很多映射器的话，采用"配置MapperFactoryBean的方式创建映射器"的效率太低了，为了解决这个问题，就有了MapperScannerConfigurer，
 * 我们可以使用MapperScannerConfigurer，让它扫描特定的包，自动帮我们批量地创建映射器，这样一来，就能大大减少配置的工作量。
 *
 * 题外：声明MapperScannerConfigurer类型的bean目的是不需要我们对于每个接口都注册一个MapperFactoryBean类型的bean，从而减少配置的工作量，
 * 但是，不在配置文件中注册，并不代表这个MapperFactoryBean bean不存在，而是在扫描的过程中，通过编码的方式，动态为每个接口注册一个MapperFactoryBean
 *
 * 题外：通过MapperScannerConfigurer，发现的映射器将会使用Spring对自动探测组件默认的命名策略来命名，也就是说，如果没有发现注解，它就会使用映射器的非大写的非完全限定类名。但是如果发现了@Component或JSR-330@Named注解，它会获取名称。
 *
 * BeanDefinitionRegistryPostProcessor that searches recursively starting from a base package for interfaces and
 * registers them as {@code MapperFactoryBean}. Note that only interfaces with at least one method will be registered;
 * concrete classes will be ignored.
 * <p>
 * This class was a {code BeanFactoryPostProcessor} until 1.0.1 version. It changed to
 * {@code BeanDefinitionRegistryPostProcessor} in 1.0.2. See https://jira.springsource.org/browse/SPR-8269 for the
 * details.
 * <p>
 * The {@code basePackage} property can contain more than one package name, separated by either commas or semicolons.
 * <p>
 * This class supports filtering the mappers created by either specifying a marker interface or an annotation. The
 * {@code annotationClass} property specifies an annotation to search for. The {@code markerInterface} property
 * specifies a parent interface to search for. If both properties are specified, mappers are added for interfaces that
 * match <em>either</em> criteria. By default, these two properties are null, so all interfaces in the given
 * {@code basePackage} are added as mappers.
 * <p>
 * This configurer enables autowire for all the beans that it creates so that they are automatically autowired with the
 * proper {@code SqlSessionFactory} or {@code SqlSessionTemplate}. If there is more than one {@code SqlSessionFactory}
 * in the application, however, autowiring cannot be used. In this case you must explicitly specify either an
 * {@code SqlSessionFactory} or an {@code SqlSessionTemplate} to use via the <em>bean name</em> properties. Bean names
 * are used rather than actual objects because Spring does not initialize property placeholders until after this class
 * is processed.
 * <p>
 * Passing in an actual object which may require placeholders (i.e. DB user password) will fail. Using bean names defers
 * actual object creation until later in the startup process, after all placeholder substitution is completed. However,
 * note that this configurer does support property placeholders of its <em>own</em> properties. The
 * <code>basePackage</code> and bean name properties all support <code>${property}</code> style substitution.
 * <p>
 * Configuration sample:
 *
 * <pre class="code">
 * {@code
 *   <bean class="org.mybatis.spring.mapper.MapperScannerConfigurer">
 *       <property name="basePackage" value="org.mybatis.spring.sample.mapper" />
 *       <!-- optional unless there are multiple session factories defined -->
 *       <property name="sqlSessionFactoryBeanName" value="sqlSessionFactory" />
 *   </bean>
 * }
 * </pre>
 *
 * @author Hunter Presnall
 * @author Eduardo Macarron
 * @see MapperFactoryBean
 * @see ClassPathMapperScanner
 */
public class MapperScannerConfigurer
  implements BeanDefinitionRegistryPostProcessor, InitializingBean, ApplicationContextAware, BeanNameAware {


  // 扫描的包
  private String basePackage;

  private boolean addToConfig = true;

  private String lazyInitialization;

  private SqlSessionFactory sqlSessionFactory;

  private SqlSessionTemplate sqlSessionTemplate;

  private String sqlSessionFactoryBeanName;

  private String sqlSessionTemplateBeanName;

  private Class<? extends Annotation> annotationClass;

  private Class<?> markerInterface;

  private Class<? extends MapperFactoryBean> mapperFactoryBeanClass;

  private ApplicationContext applicationContext;

  // 在配置文件中，为当前对象，配置的beanName
  private String beanName;

  // 是否处理属性占位符
  private boolean processPropertyPlaceHolders;

  private BeanNameGenerator nameGenerator;

  private String defaultScope;

  /**
   * This property lets you set the base package for your mapper interface files.
   * <p>
   * You can set more than one package by using a semicolon or comma as a separator.
   * <p>
   * Mappers will be searched for recursively starting in the specified package(s).
   *
   * @param basePackage base package name
   */
  public void setBasePackage(String basePackage) {
    this.basePackage = basePackage;
  }

  /**
   * Same as {@code MapperFactoryBean#setAddToConfig(boolean)}.
   *
   * @param addToConfig a flag that whether add mapper to MyBatis or not
   * @see MapperFactoryBean#setAddToConfig(boolean)
   */
  public void setAddToConfig(boolean addToConfig) {
    this.addToConfig = addToConfig;
  }

  /**
   * Set whether enable lazy initialization for mapper bean.
   * <p>
   * Default is {@code false}.
   * </p>
   *
   * @param lazyInitialization Set the @{code true} to enable
   * @since 2.0.2
   */
  public void setLazyInitialization(String lazyInitialization) {
    this.lazyInitialization = lazyInitialization;
  }

  /**
   * This property specifies the annotation that the scanner will search for.
   * <p>
   * The scanner will register all interfaces in the base package that also have the specified annotation.
   * <p>
   * Note this can be combined with markerInterface.
   *
   * @param annotationClass annotation class
   */
  public void setAnnotationClass(Class<? extends Annotation> annotationClass) {
    this.annotationClass = annotationClass;
  }

  /**
   * This property specifies the parent that the scanner will search for.
   * <p>
   * The scanner will register all interfaces in the base package that also have the specified interface class as a
   * parent.
   * <p>
   * Note this can be combined with annotationClass.
   *
   * @param superClass parent class
   */
  public void setMarkerInterface(Class<?> superClass) {
    this.markerInterface = superClass;
  }

  /**
   * Specifies which {@code SqlSessionTemplate} to use in the case that there is more than one in the spring context.
   * Usually this is only needed when you have more than one datasource.
   * <p>
   *
   * @param sqlSessionTemplate a template of SqlSession
   * @deprecated Use {@link #setSqlSessionTemplateBeanName(String)} instead
   */
  @Deprecated
  public void setSqlSessionTemplate(SqlSessionTemplate sqlSessionTemplate) {
    this.sqlSessionTemplate = sqlSessionTemplate;
  }

  /**
   * Specifies which {@code SqlSessionTemplate} to use in the case that there is more than one in the spring context.
   * Usually this is only needed when you have more than one datasource.
   * <p>
   * Note bean names are used, not bean references. This is because the scanner loads early during the start process and
   * it is too early to build mybatis object instances.
   *
   * @param sqlSessionTemplateName Bean name of the {@code SqlSessionTemplate}
   * @since 1.1.0
   */
  public void setSqlSessionTemplateBeanName(String sqlSessionTemplateName) {
    this.sqlSessionTemplateBeanName = sqlSessionTemplateName;
  }

  /**
   * Specifies which {@code SqlSessionFactory} to use in the case that there is more than one in the spring context.
   * Usually this is only needed when you have more than one datasource.
   * <p>
   *
   * @param sqlSessionFactory a factory of SqlSession
   * @deprecated Use {@link #setSqlSessionFactoryBeanName(String)} instead.
   */
  @Deprecated
  public void setSqlSessionFactory(SqlSessionFactory sqlSessionFactory) {
    this.sqlSessionFactory = sqlSessionFactory;
  }

  /**
   * Specifies which {@code SqlSessionFactory} to use in the case that there is more than one in the spring context.
   * Usually this is only needed when you have more than one datasource.
   * <p>
   * Note bean names are used, not bean references. This is because the scanner loads early during the start process and
   * it is too early to build mybatis object instances.
   *
   * @param sqlSessionFactoryName Bean name of the {@code SqlSessionFactory}
   * @since 1.1.0
   */
  public void setSqlSessionFactoryBeanName(String sqlSessionFactoryName) {
    this.sqlSessionFactoryBeanName = sqlSessionFactoryName;
  }

  /**
   * Specifies a flag that whether execute a property placeholder processing or not.
   * <p>
   * The default is {@literal false}. This means that a property placeholder processing does not execute.
   *
   * @param processPropertyPlaceHolders a flag that whether execute a property placeholder processing or not
   * @since 1.1.1
   */
  public void setProcessPropertyPlaceHolders(boolean processPropertyPlaceHolders) {
    this.processPropertyPlaceHolders = processPropertyPlaceHolders;
  }

  /**
   * The class of the {@link MapperFactoryBean} to return a mybatis proxy as spring bean.
   *
   * @param mapperFactoryBeanClass The class of the MapperFactoryBean
   * @since 2.0.1
   */
  public void setMapperFactoryBeanClass(Class<? extends MapperFactoryBean> mapperFactoryBeanClass) {
    this.mapperFactoryBeanClass = mapperFactoryBeanClass;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setApplicationContext(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setBeanName(String name) {
    this.beanName = name;
  }

  /**
   * Gets beanNameGenerator to be used while running the scanner.
   *
   * @return the beanNameGenerator BeanNameGenerator that has been configured
   * @since 1.2.0
   */
  public BeanNameGenerator getNameGenerator() {
    return nameGenerator;
  }

  /**
   * Sets beanNameGenerator to be used while running the scanner.
   *
   * @param nameGenerator the beanNameGenerator to set
   * @since 1.2.0
   */
  public void setNameGenerator(BeanNameGenerator nameGenerator) {
    this.nameGenerator = nameGenerator;
  }

  /**
   * Sets the default scope of scanned mappers.
   * <p>
   * Default is {@code null} (equiv to singleton).
   * </p>
   *
   * @param defaultScope the default scope
   * @since 2.0.6
   */
  public void setDefaultScope(String defaultScope) {
    this.defaultScope = defaultScope;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void afterPropertiesSet() throws Exception {
    // 校验是否配置了basePackage，如果没配置，则报错！
    notNull(this.basePackage, "Property 'basePackage' is required"/* 属性“basePackage”是必需的 */);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // left intentionally blank
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.0.2
   */
  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    /* 1、替换${...}属性占位符 */
    /**
     * 1、是在PropertyResourceConfigurer#postPostBeanFactory()中完成了对BeanFactory中所有bd的"替换${...}属性占位符"操作；
     * PropertyResourceConfigurer只实现了BeanFactoryPostProcessor，没有实现BeanDefinitionRegistryPostProcessor；
     * MapperScannerConfigurer实现了BeanDefinitionRegistryPostProcessor；
     * 而BeanDefinitionRegistryPostProcessor会在应用启动的时候调用，并且早于BeanFactoryPostProcessor的调用。
     * 这就意味着，当前MapperScannerConfigurer#postProcessBeanDefinitionRegistry()调用的时候；
     * PropertyResourceConfigurer bean还未被创建，PropertyResourceConfigurer#postPostBeanFactory()也未被调用；
     * 也就意味着属性文件没有被加载，"替换${...}属性占位符"操作也没有被执行，所有对属性文件中的属性引用都将失效。如果MapperScannerConfigurer当中引用了属性文件中的属性，则是失效的！
     *
     *
     * 为了避免这种情况的发生，所以提供了手动创建和获取PropertyResourceConfigurer bean —— 创建了PropertyResourceConfigurer bean就会加载到对应配置文件中的属性；
     * 并提前调用PropertyResourceConfigurer#postPostBeanFactory()，对当前MapperScannerConfigurer bd中的"${...}属性占位符"进行替换的操作，
     * 这样我们当前方法下面的代码逻辑中就能应用属性文件中的属性，进行处理了！
     *
     * 例如：
     * （1）我们有一个属性文件test.properties，里面存在的属性是
     *  basePackage=com.msb.mybatis_06.dao
     * （2）然后在Spring配置文件中加入属性文件解析器：
     *  <bean id="mesHandler" class="org.Springframework.beans.factory.config.PropertyPlaceholderConfigurer>
     *    <property name="location" value="classpath:test.properties"/>
     *  </bean>
     * （3）然后我配置的MapperScannerConfigurer bd，引入了test.properties属性文件中的${basePackage}属性
     *  <bean class="org.mybatis.spring.mapper.MapperScannerConfigurer">
     *    <property name="basePackage" value="${basePackage}"/>
     *  </bean>
     * （4）然后到达MapperScannerConfigurer#postProcessBeanDefinitionRegistry()，也就是当前方法，会发现${basePackage}并没有生效，basePackage属性为null，
     * 这是因为PropertyPlaceholderConfigurer bean 还没有被创建，PropertyPlaceholderConfigurer#postPostBeanFactory()还没有被调用，属性文件中的属性还没有加载至内存中，还没有被调用进行"替换${...}属性占位符"操作，
     * （5）为了解决这个问题，MapperScannerConfigurer提供了processPropertyPlaceHolders属性，processPropertyPlaceHolders属性为true，则可以提前创建和获取PropertyPlaceholderConfigurer bean，
     * 然后调用PropertyPlaceholderConfigurer#postPostBeanFactory()，将test.properties属性文件中的属性加载到内存中，完成对当前MapperScannerConfigurer bd中${basePackage}属性占位符的替换，
     * 替换为test.properties属性文件中的com.msb.mybatis_06.dao，然后应用在当前MapperScannerConfigurer bean的basePackage属性中，
     * 这样当前MapperScannerConfigurer.basePackage=com.msb.mybatis_06.dao，也就应用成功了属性文件中的属性了！
     *  <bean class="org.mybatis.spring.mapper.MapperScannerConfigurer">
     *    <property name="basePackage" value="${basePackage}"/>
     *    <property name="processPropertyPlaceHolders" value="true"/>
     *  </bean>
     */
    if (this.processPropertyPlaceHolders) {
      // 替换${...}属性占位符
      processPropertyPlaceHolders();
    }

    /* 2、默认，扫描包下的所有接口，构建对应的BeanDefinition(ScannedGenericBeanDefinition)，注册到spring的beanFactory中 */
    ClassPathMapperScanner scanner = new ClassPathMapperScanner(registry);
    scanner.setAddToConfig(this.addToConfig);
    scanner.setAnnotationClass(this.annotationClass);
    scanner.setMarkerInterface(this.markerInterface);
    scanner.setSqlSessionFactory(this.sqlSessionFactory);
    scanner.setSqlSessionTemplate(this.sqlSessionTemplate);
    scanner.setSqlSessionFactoryBeanName(this.sqlSessionFactoryBeanName);
    scanner.setSqlSessionTemplateBeanName(this.sqlSessionTemplateBeanName);
    scanner.setResourceLoader(this.applicationContext);
    scanner.setBeanNameGenerator(this.nameGenerator);
    scanner.setMapperFactoryBeanClass(this.mapperFactoryBeanClass);
    if (StringUtils.hasText(lazyInitialization)) {
      scanner.setLazyInitialization(Boolean.valueOf(lazyInitialization));
    }
    if (StringUtils.hasText(defaultScope)) {
      scanner.setDefaultScope(defaultScope);
    }

    // 注册过滤器
    // 题外：过滤器的作用：筛选哪些.java文件作为dao bd
    scanner.registerFilters();

    /**
     * 1、ClassPathMapperScanner当前是没有scan()的，ClassPathMapperScanner extends ClassPathBeanDefinitionScanner，
     * 在ClassPathBeanDefinitionScanner当中有scan()，所以走的是ClassPathBeanDefinitionScanner#scan()
     *
     * 2、ClassPathMapperScanner当中实现了doScan()，所以在ClassPathBeanDefinitionScanner#scan()内部的doScan()，走的是ClassPathMapperScanner#doScan()
     */
    // 扫描包下的所有接口，构建对应的BeanDefinition(ScannedGenericBeanDefinition)，注册到spring的beanFactory中
    scanner.scan(
      // 我们有可能配置多个包路径，所以将我们配置的包路径，转换为字符串数据
      StringUtils.tokenizeToStringArray(this.basePackage, ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS));
  }

  /**
   * 替换${...}属性占位符，应用属性文件中的属性
   */
  /*
   * BeanDefinitionRegistries are called early in application startup, before BeanFactoryPostProcessors. This means that
   * PropertyResourceConfigurers will not have been loaded and any property substitution of this class' properties will
   * fail. To avoid this, find any PropertyResourceConfigurers defined in the context and run them on this class' bean
   * definition. Then update the values.
   *
   * 上面的翻译：
   * BeanDefinitionRegistries 在应用程序启动的早期被调用，在 BeanFactoryPostProcessors 之前。
   * 这意味着不会加载 PropertyResourceConfigurers，并且此类属性的任何属性替换都将失败。
   * 为避免这种情况，请找到上下文中定义的任何 PropertyResourceConfigurers，并在此类的 bean 定义上运行它们。然后更新值。
   */
  private void processPropertyPlaceHolders() {

    /* 1、获取配置的PropertyResourceConfigurer bean。如果没有，则会创建。 */
    Map<String, PropertyResourceConfigurer> prcs = applicationContext.getBeansOfType(PropertyResourceConfigurer.class,
      false, false);

    if (!prcs.isEmpty() && applicationContext instanceof ConfigurableApplicationContext) {
      /* 2、获取当前MapperScannerConfigurer bd */
      // 获取当前MapperScannerConfigurer bd
      BeanDefinition mapperScannerBean = ((ConfigurableApplicationContext) applicationContext).getBeanFactory()
        .getBeanDefinition(beanName);

      // PropertyResourceConfigurer does not expose any methods to explicitly perform
      // property placeholder substitution. Instead, create a BeanFactory that just
      // contains this mapper scanner and post process the factory.
      // 上面的翻译：PropertyResourceConfigurer不公开任何方法来显式执行属性占位符替换。相反，创建一个仅包含此映射器扫描仪的BeanFactory并对工厂进行后处理。

      /* 3、创建一个DefaultListableBeanFactory，来模拟Spring中的环境，注册当前MapperScannerConfigurer bd */
      // 创建一个new DefaultListableBeanFactory()来模拟Spring中的环境(完成处理器的调用后便失效)
      DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
      // 注册当前MapperScannerConfigurer bd到新的为了模拟Spring环境的DefaultListableBeanFactory
      factory.registerBeanDefinition(beanName, mapperScannerBean);

      /* 4、调用PropertyResourceConfigurer#postProcessBeanFactory()，替换MapperScannerConfigurer bd中的${..}属性占位符，应用属性文件中的属性值 */
      // 调用PropertyResourceConfigurer#postProcessBeanFactory()，替换BeanFactory中所有bd的${..}属性占位符，
      // 由于当前BeanFactory中的bd只有MapperScannerConfigurer bd，所以是替换MapperScannerConfigurer bd中的${..}属性占位符！将属性文件中的属性添加到MapperScannerConfigurer bd中
      for (PropertyResourceConfigurer prc : prcs.values()) {
        prc.postProcessBeanFactory(factory);
      }

      /* 5、从MapperScannerConfigurer bd中，获取对应属性名的属性值，应用到当前MapperScannerConfigurer bean中 */

      // 获取属性值集合
      PropertyValues values = mapperScannerBean.getPropertyValues();

      // 从values中获取"basePackage"属性名对应的属性值
      this.basePackage = getPropertyValue("basePackage", values);
      this.sqlSessionFactoryBeanName = getPropertyValue("sqlSessionFactoryBeanName", values);
      this.sqlSessionTemplateBeanName = getPropertyValue("sqlSessionTemplateBeanName", values);
      this.lazyInitialization = getPropertyValue("lazyInitialization", values);
      this.defaultScope = getPropertyValue("defaultScope", values);
    }

    /* 6、如果配置了对应的属性，则调用Environment.resolvePlaceholders()解决里面的占位符！ */

    // 如果basePackage不为null(配置了basePackage属性)，则调用Environment.resolvePlaceholders()解决里面的占位符！
    this.basePackage = Optional.ofNullable(this.basePackage)
      .map(getEnvironment()::resolvePlaceholders/* 解决占位符 */).orElse(null);
    this.sqlSessionFactoryBeanName = Optional.ofNullable(this.sqlSessionFactoryBeanName)
      .map(getEnvironment()::resolvePlaceholders).orElse(null);
    this.sqlSessionTemplateBeanName = Optional.ofNullable(this.sqlSessionTemplateBeanName)
      .map(getEnvironment()::resolvePlaceholders).orElse(null);
    this.lazyInitialization = Optional.ofNullable(this.lazyInitialization)
      .map(getEnvironment()::resolvePlaceholders).orElse(null);
    this.defaultScope = Optional.ofNullable(this.defaultScope)
      .map(getEnvironment()::resolvePlaceholders).orElse(null);
  }

  private Environment getEnvironment() {
    return this.applicationContext.getEnvironment();
  }

  /**
   * 从PropertyValues中获取对应属性名的属性值
   *
   * @param propertyName          属性名
   * @param values                PropertyValues
   */
  private String getPropertyValue(String propertyName, PropertyValues values) {
    // 从PropertyValues中获取对应属性名的属性值
    PropertyValue property = values.getPropertyValue(propertyName);

    // 不存在PropertyValue，则返回null
    if (property == null) {
      return null;
    }

    // 存在PropertyValue，则获取属性值
    Object value = property.getValue();

    // 属性值为null，则返回null
    if (value == null) {
      return null;
    }
    // 属性值是String类型，则调用toString()，然后返回
    else if (value instanceof String) {
      return value.toString();
    }
    // 属性值是TypedStringValue（也代表String类型），则调用getValue()获取属性值
    else if (value instanceof TypedStringValue) {
      return ((TypedStringValue) value).getValue();
    }
    // 其余返回null
    else {
      return null;
    }
  }

}
