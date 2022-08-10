/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * @author Clinton Begin
 * MetaClass 可以解析表达式中属性对应的类型，
 * 能够用于解析字符串类型的表达式。
 * 但是目前来看，MetaClass 的 getGetterType 方法能够处理具有下标的表达式，
 * 但是 getSetterType 不能够正常处理下标，而且 getGetterType 仅能处理单个泛型参数的集合类，
 * 如 List<String>，不能处理多个参数的集合类，
 * 如 MyList<A, B> extends ArrayList，getGetterType 无法处理集合类：MyList<String, Integer>。
 */
public class MetaClass {

  /**
   * 反射工厂
   */
  private final ReflectorFactory reflectorFactory;
  /**
   * 反射器
   */
  private final Reflector reflector;

  /**
   * 构造函数
   * @param type 反射类
   * @param reflectorFactory 反射工厂
   */
  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    this.reflector = reflectorFactory.findForClass(type);
  }


  /**
   * 创建指定类的MetaClass对象
   */
  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

  /**
   * 创建指定属性类型的MetaClass对象
   */
  public MetaClass metaClassForProperty(String name) {
    Class<?> propType = reflector.getGetterType(name);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 获取指定属性
   * @param name  指定属性名称
   */
  public String findProperty(String name) {
    // 构建属性
    StringBuilder prop = buildProperty(name, new StringBuilder());

    return prop.length() > 0 ? prop.toString() : null;
  }

  /**
   * 获取指定属性
   * @param name 指定属性名称
   * @param useCamelCaseMapping 下划线转驼峰标志
   */
  public String findProperty(String name, boolean useCamelCaseMapping) {
    // 1. 下划线转驼峰
    if (useCamelCaseMapping) {
      name = name.replace("_", "");
    }
    // 2. 获取属性
    return findProperty(name);
  }

  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

  /**
   * 获取setter类型
   */
  public Class<?> getSetterType(String name) {
    // 创建分词器
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 存在子集
    if (prop.hasNext()) {
      // 创建MetaClass对象
      MetaClass metaProp = metaClassForProperty(prop.getName());
      // 返回子集类型
      return metaProp.getSetterType(prop.getChildren());
    } else {
      return reflector.getSetterType(prop.getName());
    }
  }

  /**
   * 获取getter类型
   */
  public Class<?> getGetterType(String name) {
    // 创建分词器
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 存在子集
    if (prop.hasNext()) {
      // 构建属性对应的 MetaClass
      MetaClass metaProp = metaClassForProperty(prop);
      // 处理属性对应的子表达式
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    return getGetterType(prop);
  }

  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    Class<?> propType = getGetterType(prop);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 解析属性对应的类型信息，如果是带下标的集合类型，则会解析为实际类型
   */
  private Class<?> getGetterType(PropertyTokenizer prop) {
    // 获取类型
    Class<?> type = reflector.getGetterType(prop.getName());
    // 指定下标元素,并且是 Collection 的 子类
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
      // 解析属性的类型
      Type returnType = getGenericGetterType(prop.getName());
      // 如果是带有泛型参数的集合类型
      if (returnType instanceof ParameterizedType) {
        // 获取泛型的实际类型列表
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
        // 仅包含 1 个泛型参数
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          returnType = actualTypeArguments[0];
          // 将泛型参数的类型作为实际的返回值类型
          if (returnType instanceof Class) {
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
            // 如果泛型参数还是带有泛型参数的类型，则直接返回泛型参数的实际类型，而不继续向下解析
            // 如，解析字段 List<List<B>> b，解析表达式是 b[0]，会解析出 List 是泛型类型，且具有唯一的泛型参数 List<B>
            // 此时 List<B> 是 ParameterizedType，则不继续向下层解析，直接返回 List.class 作为 b[0] 的类型
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }

  /**
   * 解析指定属性的类型
   */
  private Type getGenericGetterType(String propertyName) {
    try {
      // 获取Invoker对象
      Invoker invoker = reflector.getGetInvoker(propertyName);
      // 如果是getter方法
      if (invoker instanceof MethodInvoker) {
        // 通过反射，直接获取 MethodInvoker.method 代表的 Method 对象，并返回该方法的返回值类型
        Field _method = MethodInvoker.class.getDeclaredField("method");
        _method.setAccessible(true);
        Method method = (Method) _method.get(invoker);
        // 解析方法的返回值类型，包含参数的实际类型和泛型参数的类型
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      // 如果对象是字段
      } else if (invoker instanceof GetFieldInvoker) {
        // 通过反射直接获取字段,并返回该字段类型
        Field _field = GetFieldInvoker.class.getDeclaredField("field");
        _field.setAccessible(true);
        Field field = (Field) _field.get(invoker);
        // 解析字段类型，包含字段的实际类型和泛型参数的类型
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException | IllegalAccessException ignored) {
    }
    return null;
  }

  /**
   * 判断是否存在setter方法
   */
  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasSetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop.getName());
        return metaProp.hasSetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasSetter(prop.getName());
    }
  }

  /**
   * 判断是否存在getter方法
   */
  public boolean hasGetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasGetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop);
        return metaProp.hasGetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasGetter(prop.getName());
    }
  }

  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }

  private StringBuilder buildProperty(String name, StringBuilder builder) {
    // 创建PropertyTokenizer 对 name进行分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 存在子表达式
    if (prop.hasNext()) {
      // 获取属性名称
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        builder.append(propertyName);
        builder.append(".");
        // 创建MetaClass 对子属性进行拼接
        MetaClass metaProp = metaClassForProperty(propertyName);
        metaProp.buildProperty(prop.getChildren(), builder);
      }
    // 不存在子表达式
    } else {
      // 获取属性名称
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
