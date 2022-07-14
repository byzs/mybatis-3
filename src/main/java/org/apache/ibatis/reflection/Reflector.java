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

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 *
 * 反射器
 */
public class Reflector {

  /**
   * 指定类型
   */
  private final Class<?> type;
  /**
   * 可读属性组
   */
  private final String[] readablePropertyNames;
  /**
   * 可写属性组
   */
  private final String[] writeablePropertyNames;
  /**
   * 属性setting方法映射
   *
   * key 属性名
   * value Invoker对象
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();
  /**
   * 属性getting方法映射
   *
   * key 属性名
   * value Invoker对象
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();
  /**
   * 属性setting方法返回类型 {@link #setMethods}
   *
   * key 属性名
   * value 返回类型
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  /**
   * 属性getting方法返回类型 {@link #getMethods}
   *
   * key 属性名
   * value 返回类型
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  /**
   * 默认构造函数
   */
  private Constructor<?> defaultConstructor;
  /**
   * 不区分大小写的属性集合
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
    // 1.指定类型
    type = clazz;
    // 2.默认构造函数初始化
    addDefaultConstructor(clazz);
    // 3.getMethods,getTypes初始化
    addGetMethods(clazz);
    // 4.setMethods,setTypes初始化
    addSetMethods(clazz);
    // 5.set get 初始化的补充,有些 field ，不存在对应的 setting 或 getting 方法，所以直接使用对应的 field ，而不是方法
    addFields(clazz);
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writeablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  /**
   * 获取 clazz 的 无参构造, 赋值 {@link #defaultConstructor}
   *
   * @param clazz 指定类型
   */
  private void addDefaultConstructor(Class<?> clazz) {
    // 获取所有构造函数
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    // 遍历获取无参构造
    for (Constructor<?> constructor : consts) {
      if (constructor.getParameterTypes().length == 0) {
          this.defaultConstructor = constructor;
      }
    }
  }

  /**
   * getMethod getTypes 初始化
   */
  private void addGetMethods(Class<?> cls) {
    // 1.属性与getting方法映射容器
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // 2.获取所有方法
    Method[] methods = getClassMethods(cls);
    // 3.遍历所有方法
    for (Method method : methods) {
      // 3.1 参数 > 0 表明不是get方法
      if (method.getParameterTypes().length > 0) {
        continue;
      }
      // 3.2 获取方法名
      String name = method.getName();
      // 3.3 判断是否符合getting方法规则
      if ((name.startsWith("get") && name.length() > 3)
          || (name.startsWith("is") && name.length() > 2)) {
        // 3.4 获取属性名
        name = PropertyNamer.methodToProperty(name);
        // 3.5 存入映射集中
        addMethodConflict(conflictingGetters, name, method);
      }
    }
    // 解决方法冲突
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * 解决方法冲突
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 getting 方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      // 最匹配的方法
      Method winner = null;
      String propName = entry.getKey();
      for (Method candidate : entry.getValue()) {
        // winner 为空，说明属性对应的方法只有一个
        if (winner == null) {
          winner = candidate;
          continue;
        }
        // 基于返回类型进行比较
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        // 返回值类型相同
        if (candidateType.equals(winnerType)) {
          // 两个方法的返回值类型一致，若两个方法返回值类型均为 boolean，则选取 isXXX 方法为 winner。否则无法决定哪个方法更为合适
          if (!boolean.class.equals(candidateType)) {
            throw new ReflectionException(
                "Illegal overloaded getter method with ambiguous type for property "
                    + propName + " in class " + winner.getDeclaringClass()
                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
          // boolean 类型
          } else if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
        // 不符合选择子类
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // OK getter type is descendant
        // 符合选择子类。因为子类可以修改放大返回值。例如，父类的一个方法的返回值为 List ，子类对该方法的返回值可以覆写为 ArrayList
        } else if (winnerType.isAssignableFrom(candidateType)) {
          winner = candidate;
        // 返回类型冲突，抛出 ReflectionException 异常
        } else {
          throw new ReflectionException(
              "Illegal overloaded getter method with ambiguous type for property "
                  + propName + " in class " + winner.getDeclaringClass()
                  + ". This breaks the JavaBeans specification and can cause unpredictable results.");
        }
      }
      // 添加到 getMethods 和 getTypes 中
      addGetMethod(propName, winner);
    }
  }

  private void addGetMethod(String name, Method method) {
    // 判断是合理的属性名
    if (isValidPropertyName(name)) {
      // 添加到 getMethods 中
      getMethods.put(name, new MethodInvoker(method));
      // 添加到 getTypes 中
      Type returnType = TypeParameterResolver.resolveReturnType(method, type);
      getTypes.put(name, typeToClass(returnType));
    }
  }

  private void addSetMethods(Class<?> cls) {
    // 1.属性和setting方法映射集合
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    // 2.获取所有方法
    Method[] methods = getClassMethods(cls);
    // 3.筛选set方法
    for (Method method : methods) {
      String name = method.getName();
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {
          name = PropertyNamer.methodToProperty(name);
          // 3.1 放入映射集合中
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    // 4.解决方法冲突
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    // computeIfAbsent 当指定key不存在,新建一个集合并且放入到map容器中
    List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
    list.add(method);
  }

  /**
   * 解决方法冲突
   */
  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 setting 方法
    for (String propName : conflictingSetters.keySet()) {
      // 对应属性的所有方法
      List<Method> setters = conflictingSetters.get(propName);
      // 获取该属性类型
      Class<?> getterType = getTypes.get(propName);
      Method match = null;
      ReflectionException exception = null;
      // 遍历属性对应的 setting 方法
      for (Method setter : setters) {
        // set参数类型
        Class<?> paramType = setter.getParameterTypes()[0];
        // 类型相同,直接使用改方法
        if (paramType.equals(getterType)) {
          match = setter;
          break;
        }
        // 多个方法对比选择最适合的
        if (exception == null) {
          try {
            //选择最适合的方法
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      // 添加到 setMethods, setTypes中
      if (match == null) {
        throw exception;
      } else {
        addSetMethod(propName, match);
      }
    }
  }
  /**
   * 对比两个方法选择最匹配的一个
   */
  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    // setter1 为null 直接返回setter2
    if (setter1 == null) {
      return setter2;
    }
    // 方法参数类型
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    // 优先选择子类, 比如 List ArrayList ,优先选择ArrayList更细化的类型
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  private void addSetMethod(String name, Method method) {
    // 属性名是否符合规范
    if (isValidPropertyName(name)) {
      // 添加到 setMethods setTypes 容器中
      setMethods.put(name, new MethodInvoker(method));
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  /**
   * 获取指定类型对用的类
   */
  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    // 普通类型直接使用
    if (src instanceof Class) {
      result = (Class<?>) src;
    // 泛型使用泛型
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    // 泛型数组,使用具体类
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    // 都不符合，使用 Object 类
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    // 获取所有 field
    Field[] fields = clazz.getDeclaredFields();
    // 遍历
    for (Field field : fields) {
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          addSetField(field);
        }
      }
      if (!getMethods.containsKey(field.getName())) {
        addGetField(field);
      }
    }
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler Class.getMethods(),
   * because we want to look for private methods as well.
   *
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
    // 1.方法签名与方法的映射容器
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = cls;
    // 2.类循环,直到基类为止
    while (currentClass != null && currentClass != Object.class) {
      // 3.记录当前类定义的方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // 4.记录接口中的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      // 5.获取基类
      currentClass = currentClass.getSuperclass();
    }

    // 转换为 method 数组返回
    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[methods.size()]);
  }

  /**
   * 类方法添加到 方法签名与方法映射的容器中
   *
   * @param uniqueMethods 方法签名与方法映射的容器
   * @param methods 方法
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      // 忽略桥接方法
      if (!currentMethod.isBridge()) {
        // 获取方法签名
        String signature = getSignature(currentMethod);
        // 不存在签名时添加到容器中
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 获取方法签名
   *
   * @param method 方法
   * @return String 格式为 returnType#方法名:参数名1,参数名2,参数名3
   */
  private String getSignature(Method method) {
    // 签名拼接容器
    StringBuilder sb = new StringBuilder();
    // 获取方法返回类型
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      // 类型拼接
      sb.append(returnType.getName()).append('#');
    }
    // 方法名拼接
    sb.append(method.getName());
    // 获取方法参数
    Class<?>[] parameters = method.getParameterTypes();
    // 方法参数拼接
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    // 返回签名
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writeablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
