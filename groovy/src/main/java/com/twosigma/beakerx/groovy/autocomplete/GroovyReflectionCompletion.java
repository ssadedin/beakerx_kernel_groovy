/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.twosigma.beakerx.groovy.autocomplete;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.StringUtils;

import com.twosigma.beakerx.autocomplete.AutocompleteResult;
import com.twosigma.beakerx.kernel.Imports;

import groovy.lang.Binding;

/**
 * Support to autocomplete editor contents based on runtime discovery of compatible
 * extensions using reflection.
 * 
 * @author simon.sadedin
 */
public class GroovyReflectionCompletion {
  
  Binding binding;
  
  ClassLoader gcl;
  
  Imports imports;

  private Pattern indexedAccessPattern = Pattern.compile("(.*)\\[([0-9]{1,})\\]");
  
  public GroovyReflectionCompletion(Binding binding, ClassLoader classLoader, Imports imports) {
    this.binding = binding;
    this.gcl = classLoader;
    this.imports = imports;
  }

  public AutocompleteResult autocomplete(String text, int pos) {
    
    AutocompleteResult constructorResults = null;
    try {
      constructorResults = tryResolveConstructor(text, pos);
    } catch (ClassNotFoundException e) {
      // Ignore: try to complete based on standard expression
      e.printStackTrace();
    }

    AutocompleteResult expressionResults = null;
    try {
      expressionResults = this.autocompleteExpression(text, pos);
    } catch (Exception e) {
      // Ignore: use other results instead
      e.printStackTrace();
    }
    
    AutocompleteResult allResults = new AutocompleteResult(new ArrayList<>(), pos);
    if(constructorResults != null)
      allResults = allResults.append(constructorResults);
    if(expressionResults != null) {
      allResults = allResults.append(expressionResults);
      allResults.setStartIndex(expressionResults.getStartIndex());
    }
    
    return allResults;
  }
    
  public AutocompleteResult autocompleteExpression(String text, int pos) {
    String expr = resolveExpression(text,pos-1);

    int replacementStartIndex = Math.min(text.length(), findReplacementStartPosition(text, pos-1)+1);
    
    ArrayList<String> parts = new ArrayList<String>();
    StringTokenizer tokenizer = new StringTokenizer(expr, ".");
    while(tokenizer.hasMoreTokens()) {
      parts.add(tokenizer.nextToken());
    }
    
    if(expr.endsWith("."))
      parts.add("");
    
    String bindingReference = parts.get(0);
    Matcher m = indexedAccessPattern.matcher(bindingReference);
    if(m.matches()) {
      bindingReference = m.group(1);
    }
  
    if(binding.hasVariable(bindingReference) && ((parts.size() > 1))) {
      return autocompleteFromObject(parts, replacementStartIndex, pos);
    }
    else  {
      List<String> result = 
        ((Map<String,Object>)binding.getVariables())
                      .keySet()
                      .stream()
                      .filter(x -> x.startsWith(expr))
                      .filter(x -> !x.equals(expr))
                      .collect(Collectors.toList());

      return new AutocompleteResult(result, replacementStartIndex);
    }
  }
  
  final static Pattern CONSTRUCTOR_PATTERN = Pattern.compile("new ([A-Za-z0-9_]{1,})\\((\\s*[a-z0-9_A-Z]*:.*,\\s*){0,}\\s*([a-z0-9_A-Z]*)@@@\\s*\\)");
  
  public class ConstructorMatch {
    String text;
    int pos;
    public String className;
    public String propName;
    public ConstructorMatch(String text, int pos, String className, String propName) {
      this.text = text;
      this.pos = pos;
      this.className = className;
      this.propName = propName;
    }

    public List<String> computeConstructorCompletions() throws ClassNotFoundException {

      Class clazz = findClassMatch();
      ArrayList<String> result = new ArrayList<String>();
      getClassMutablePropertyNames(clazz).forEach(prop -> {
        if(this.propName != null && !this.propName.isEmpty()) {
          if(!prop.startsWith(this.propName))
            return;
        }
        result.add(prop + ": ");
      });
      
      return result;
    }

    private Class findClassMatch() throws ClassNotFoundException {
      
      final String dotClassName = "." + className;
      
      List<String> classesToTry = new ArrayList<String>();
      classesToTry.add(className);
      
      imports.getImportPaths()
        .stream()
        .filter(p -> !p.isStatic())
        .map(p -> p.path())
        .filter(path -> {
          return path.endsWith(".*") || path.endsWith(dotClassName);
        })
        .map(path -> {
          if(path.endsWith(".*")) {
            return path.substring(0,path.length()-2) + dotClassName;
          }
          else {
            return path;
          }
        })
        .forEach(classesToTry::add);

      Iterator<String> i = classesToTry.iterator();
      for(String classToTry = i.next(); i.hasNext(); classToTry = i.next()) {
        Class clazz = tryLoadClass(classToTry);
        if(clazz != null)
          return clazz;
      }
      return null;
    }
  }
  
  private Class tryLoadClass(String className) {
    try {
      return gcl.loadClass(className);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  public AutocompleteResult tryResolveConstructor(String text, int pos) throws ClassNotFoundException {
    
    ConstructorMatch match = tryMatchConstructor(text, pos);
    if(match == null) {
      return null;
    }
    else {
      return new AutocompleteResult(match.computeConstructorCompletions(), pos);
    }
  }

  public ConstructorMatch tryMatchConstructor(String text, int pos) throws ClassNotFoundException {

    // Insert a cursor symbol at the position of the cursor to allow
    // for regex matching to it
    String matchText = text.substring(0, pos) + "@@@" + text.substring(pos);
    
    Matcher m = CONSTRUCTOR_PATTERN.matcher(matchText);
    if(m.find()) {
      String className = m.group(1);
      String propName = m.groupCount() > 1 ? m.group(m.groupCount()) : null;

      return new ConstructorMatch(text, pos, className, propName);
    }
    return null;
  }

  /**
   * Extracts the expression to be autocompleted.
   * 
   * The expression is an executable statement that would (if executed) return the
   * value that should be autocompleted.
   * 
   * @param text  the text at the cursor
   * @param pos  the position of the cursor within the text
   * @return
   */
  public String resolveExpression(String text, int pos) {
    
    int expressionEnd = pos + 1;
    int expressionStart = findExpressionStart(text, pos);
    
    expressionStart = Math.max(0,expressionStart);
    expressionEnd = Math.min(text.length(),expressionEnd);
    
    String result = text.substring(expressionStart, expressionEnd).trim();
    
    char lastChar = result.charAt(result.length()-1);
    if(lastChar != '.' && !Character.isJavaIdentifierPart(lastChar)) {
      result = result.substring(0, result.length()-1);
    }
    
    if(!Character.isJavaIdentifierPart(result.charAt(0))) {
      result = result.substring(1);
    }

    return result;
  }

  private int findExpressionStart(String text, int startPos) {
    List<Character> bracketStack = new ArrayList<Character>();

    int pos = startPos;
    while(pos >= 0) {
      char c  = text.charAt(pos);
      
      if(c == '.') {
        // allow
      }
      else
      if(Character.isJavaIdentifierPart(c)) {
        // allow
      }
      else
      if(c == ']') {
        bracketStack.add(c);
      }
      else
      if(c == '[') {
        if(!bracketStack.isEmpty() && bracketStack.get(bracketStack.size()-1) == ']') {
          bracketStack.remove(bracketStack.size()-1);
        }
        else
          break;
      }
      else
        break;

      --pos;
    }
    return pos;
  }

  /**
   * Returns the index of the first char that would be replaced by a candidate for 
   * autocomplete, ie: outside the expression to be evaluated and any non-identifier 
   * chars.
   * 
   * @param text                full text of cell containing code
   * @param startPos            position within cell to scan
   * @return index of the first char that would be replaced by a candidate
   */
  public int findReplacementStartPosition(String text, int startPos) {
    int pos = startPos;
    while(pos >= 0) {
      final char c  = text.charAt(pos);
      if(!Character.isJavaIdentifierPart(c)) {
        break;
      }
      --pos;
    }
    return Math.max(0, pos);
  }
  
  /**
   * These are groovy-fied methods that do not show up in a nice groovy way by reflection
   */
  public static final List<String> SUPPLEMENTARY_COLLECTION_COMPLETIONS = Arrays.asList(
        "isEmpty()", 
        "size()", 
        "collectEntries { ", 
        "collect { ", 
        "find { ", 
        "grep { ",
        "groupBy { ",
        "countBy { "
    );
  
  public final static List<String> STRING_COMPLETIONS = Arrays.asList(
      "size()",
      "split(",
      "tokenize(",
      "matches(",
      "contains("
  );
  
  AutocompleteResult autocompleteFromObject(List<String> parts, int replacementStartIndex, int pos) {
    
    List<String> lowPriorityCompletions = Arrays.asList("class","metaClass");
    List<String> filteredCompletions = Arrays.asList("empty");
    List<String> iterableOnlyCompletions = Arrays.asList("join(");

    AutocompleteResult result = new AutocompleteResult(replacementStartIndex);
    try {

      String bindingReference = parts.get(0);
      Matcher m = indexedAccessPattern.matcher(bindingReference);
      
      Object value;
      if(m.matches()) {
        List collValue = (List)binding.getVariable(m.group(1));
        value = collValue.get(Integer.parseInt(m.group(2)));
      }
      else
        value = binding.getVariable(bindingReference);

      int i = 1;
      for(; i<parts.size()-1; ++i) {
        String partExpr = parts.get(i);
        Matcher m2 = indexedAccessPattern.matcher(partExpr);
        if(m2.matches()) {
          value = PropertyUtils.getIndexedProperty(value, partExpr);
        }
        else {
          value = PropertyUtils.getSimpleProperty(value, partExpr);
        }
      
        if(value == null) {
          // We can't complete anything on it
          // TODO: we could complete on the static type one day
          return new AutocompleteResult(replacementStartIndex);
        }
      }
      
      String completionToken = parts.size() > 1 ? parts.get(parts.size()-1) : "";
      
      List<String> properties = getObjectPropertyNames(value); 
      
      List<String> lowPri = new ArrayList<String>();
    
      final Object resolvedValue = value;
      properties.forEach((String key) -> {
        if(key.startsWith(completionToken)) {
          if(lowPriorityCompletions.contains(key)) {
            lowPri.add(key);
          }
          else {
            try {
              var propClass = PropertyUtils.getPropertyType(resolvedValue, key);
              var typeInfo = propClass != null ? ("Property: " + propClass.getSimpleName()) : "";
              result.add(key, typeInfo);
            } 
            catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
              result.add(key);
            }
          }
        }
      });
      
      if(value instanceof Map) {
        Map<String,?> mapValue = (Map<String,?>)value;
        mapValue.keySet().stream()
                .filter(k -> k.startsWith(completionToken))
                .forEach(k -> {
                  Object v = mapValue.get(k);
                  result.add(k, "Map entry: " + v.getClass().getSimpleName()); 
                });
      }
      
      if(value instanceof Iterable || value instanceof Map) {
        result.addAll(SUPPLEMENTARY_COLLECTION_COMPLETIONS, "Groovy built in collection functions");
        result.addAll(iterableOnlyCompletions, "Groovy built in iterable functions");
      }
      
      if(value instanceof String) {
        result.addAll(STRING_COMPLETIONS, "String");
      }
      
      result.removeIf(v -> !v.match.startsWith(completionToken));
      result.removeIf(v -> filteredCompletions.contains(v.match));
      
      // Finally, add method names
      result.append(getObjectMethodCompletions(value, replacementStartIndex, completionToken));
  
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      e.printStackTrace();
    }
    return result;
  }

  private List<String> getObjectPropertyNames(Object value) {
    return getClassPropertyNames(value.getClass());
  }
  
  private List<String> getClassMutablePropertyNames(final Class clazz) {
    Stream<Method> methods = Stream.of(clazz.getMethods());

    List<String> properties = 
          methods.filter(m -> 
          m.getName().startsWith("set") && 
          m.getName().length() > 3 && 
          Character.isUpperCase(m.getName().charAt(3)) 
          && java.lang.reflect.Modifier.isPublic(m.getModifiers()) &&
          m.getParameters().length == 1
          )
          .map((Method m) -> 
           StringUtils.uncapitalize(m.getName().substring(3))
          )
          .collect(Collectors.toList());
    return properties;
  }
  

  private List<String> getClassPropertyNames(final Class clazz) {
    Stream<Method> methods = Stream.of(clazz.getMethods());

    List<String> properties = 
          methods.filter(m -> 
          m.getName().startsWith("get") && 
          m.getName().length() > 3 && 
          Character.isUpperCase(m.getName().charAt(3)) 
          && java.lang.reflect.Modifier.isPublic(m.getModifiers()) &&
          m.getParameters().length == 0
          )
          .map((Method m) -> 
           StringUtils.uncapitalize(m.getName().substring(3))
          )
          .collect(Collectors.toList());
    return properties;
  }
  
  AutocompleteResult.MatchInfo formatMethod(Method m) {
    
    List<Parameter> parameters = Arrays.asList(m.getParameters());
    
    // If the last item is a closure, leave it off and append closure brace at end
    boolean hasTrailingClosure = false;
    if(parameters.size()>0) {
      Parameter lastParameter = parameters.get(parameters.size()-1);
      if(lastParameter.getType().getSimpleName().equals("Closure")) {
        parameters = parameters.subList(0, parameters.size()-1);
        hasTrailingClosure = true;
      }
    }
    
    String types = parameters.stream()
              .map((Parameter x) -> { 
                String argType = 
                    sanitizeTypeName(x.getType().getName());
                     return argType;
              })
              .collect(Collectors.joining(", "));
    
    String names = 
        parameters.stream()
        .map(p -> p.getName())
        .collect(Collectors.joining(","));
         
    String returnType = sanitizeTypeName(m.getReturnType().getName());

    String signature = String.format("function: (%s) -> %s", types, returnType);
    String closureFragment = hasTrailingClosure ? " { " : "";
    String openParen = "(";
    String closeParen = ")";
    if(hasTrailingClosure && parameters.isEmpty()) {
      openParen="";
      closeParen="";
    }
    
    if(Stream.of(m.getParameters()).allMatch(p -> p.isNamePresent())) {
      return new AutocompleteResult.MatchInfo(m.getName() + openParen + names + closeParen + closureFragment,signature);
    }
    return new AutocompleteResult.MatchInfo(m.getName() + openParen + types + closeParen + closureFragment, signature);
  }
  
  public static final List<String> IGNORE_METHODS = 
      Arrays.asList("invokeMethod",
          "getMetaClass",
          "setMetaClass",
          "setProperty",
          "getProperty",
          "equals",
          "toString",
          "hashCode",
          "wait",
          "getClass",
          "notify",
          "notifyAll");
  
  boolean isNonPropertyMethod(final Method m) {
    
    if(m.getName().startsWith("get") && m.getParameterCount()==0)
      return false;
    
    if(m.getName().startsWith("set") && m.getParameterCount()==1)
      return false;
    
    return true;
  }
  
  AutocompleteResult getObjectMethodCompletions(final Object obj, final int replacementStartIndex, final String completionToken) {
    
    @SuppressWarnings("rawtypes")
    Class c = obj.getClass();

    AutocompleteResult result = new AutocompleteResult(replacementStartIndex);
    Stream.of(c.getMethods())
          .filter(m -> 
            isNonPropertyMethod(m) &&
            !IGNORE_METHODS.contains(m.getName()))
          .filter(m -> m.getName().startsWith(completionToken))
          .forEach( m -> {
            var matchInfo  = formatMethod(m);
            result.add(matchInfo.match, matchInfo.typeInfo);
          });
    
    return result;
  }
  
  String sanitizeTypeName(String name) {
    return name.replaceAll("java.lang.","")
        .replaceAll("java.util.","")
        .replaceAll("java.io.","")
        .replaceAll("groovy.lang.","");
  }
}