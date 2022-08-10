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
package org.apache.ibatis.reflection.factory;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.ibatis.reflection.ReflectionException;
import org.apache.ibatis.reflection.Reflector;

/**
 * @author Clinton Begin
 */
public class DefaultObjectFactory implements ObjectFactory, Serializable {

  private static final long serialVersionUID = -8855120656740914948L;

  @Override
  public <T> T create(Class<T> type) {
    return create(type, null, null);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    // <1> 获得需要创建的类
    Class<?> classToCreate = resolveInterface(type);
    // we know types are assignable
    // <2> 创建指定类的对象
    return (T) instantiateClass(classToCreate, constructorArgTypes, constructorArgs);
  }

  @Override
  public void setProperties(Properties properties) {
    // no props for default
  }

  private  <T> T instantiateClass(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    try {
      Constructor<T> constructor;
      // 获取无参构造
      if (constructorArgTypes == null || constructorArgs == null) {
        constructor = type.getDeclaredConstructor();
        try {
          return constructor.newInstance();
        } catch (IllegalAccessException e) {
          // 无权限访问,修改访问权限后 再次创建
          if (Reflector.canControlMemberAccessible()) {
            constructor.setAccessible(true);
            return constructor.newInstance();
          } else {
            throw e;
          }
        }
      }
      // 获取指定的构造方法
      constructor = type.getDeclaredConstructor(constructorArgTypes.toArray(new Class[constructorArgTypes.size()]));
      try {
        return constructor.newInstance(constructorArgs.toArray(new Object[constructorArgs.size()]));
      } catch (IllegalAccessException e) {
        // 无权限访问,修改访问权限后 再次创建
        if (Reflector.canControlMemberAccessible()) {
          constructor.setAccessible(true);
          return constructor.newInstance(constructorArgs.toArray(new Object[constructorArgs.size()]));
        } else {
          throw e;
        }
      }
    } catch (Exception e) {
      StringBuilder argTypes = new StringBuilder();
      // 方法返回类型不为null且参数不为空的情况下发生了异常
      if (constructorArgTypes != null && !constructorArgTypes.isEmpty()) {
        // 遍历 方法返回类型 拼接 类型参数
        for (Class<?> argType : constructorArgTypes) {
          argTypes.append(argType.getSimpleName());
          argTypes.append(",");
        }
        // 删除最后的 ","符号
        argTypes.deleteCharAt(argTypes.length() - 1); // remove trailing ,
      }
      StringBuilder argValues = new StringBuilder();
      // 参数类型不为null,且参数不为空的情况下发生了异常
      if (constructorArgs != null && !constructorArgs.isEmpty()) {
        // 遍历参数,拼接信息
        for (Object argValue : constructorArgs) {
          argValues.append(String.valueOf(argValue));
          argValues.append(",");
        }
        // 删除符号”,“
        argValues.deleteCharAt(argValues.length() - 1); // remove trailing ,
      }
      // 抛出 详细的异常信息
      throw new ReflectionException("Error instantiating " + type + " with invalid types (" + argTypes + ") or values (" + argValues + "). Cause: " + e, e);
    }
  }

  /**
   * 接口解析，返回需要创建的类
   */
  protected Class<?> resolveInterface(Class<?> type) {
    Class<?> classToCreate;
    if (type == List.class || type == Collection.class || type == Iterable.class) {
      classToCreate = ArrayList.class;
    } else if (type == Map.class) {
      classToCreate = HashMap.class;
    } else if (type == SortedSet.class) { // issue #510 Collections Support
      classToCreate = TreeSet.class;
    } else if (type == Set.class) {
      classToCreate = HashSet.class;
    } else {
      classToCreate = type;
    }
    return classToCreate;
  }

  @Override
  public <T> boolean isCollection(Class<T> type) {
    // Collection 是 type 的继承的类,实现的接口,或者同类型
    return Collection.class.isAssignableFrom(type);
  }

}
