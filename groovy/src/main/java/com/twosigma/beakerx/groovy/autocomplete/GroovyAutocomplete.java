/*
 *  Copyright 2014 TWO SIGMA OPEN SOURCE, LLC
 *
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

import com.twosigma.beakerx.autocomplete.AutocompleteCandidate;
import com.twosigma.beakerx.autocomplete.AutocompleteRegistry;
import com.twosigma.beakerx.autocomplete.AutocompleteResult;
import com.twosigma.beakerx.autocomplete.AutocompleteServiceBeakerx;
import com.twosigma.beakerx.autocomplete.ClassUtils;
import com.twosigma.beakerx.autocomplete.MagicCommandAutocompletePatterns;
import com.twosigma.beakerx.kernel.Imports;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.twosigma.beakerx.groovy.autocomplete.AutocompleteRegistryFactory.setup;

public class GroovyAutocomplete extends AutocompleteServiceBeakerx {
  AutocompleteRegistry registry;
  private GroovyClasspathScanner cps;
  private GroovyClassLoader groovyClassLoader;
  private Imports imports;
  private Binding scriptBinding;

  public GroovyAutocomplete(GroovyClasspathScanner _cps,
                            GroovyClassLoader groovyClassLoader,
                            Imports imports,
                            MagicCommandAutocompletePatterns autocompletePatterns,
                            Binding scriptBinding) {
    super(autocompletePatterns);
    cps = _cps;
    this.groovyClassLoader = groovyClassLoader;
    this.imports = imports;
    this.scriptBinding = scriptBinding;
    registry = AutocompleteRegistryFactory.createRegistry(cps);
  }

  @Override
  protected AutocompleteResult doAutocomplete(String txt, int cur) {
    try {
      return tryFindAutocomplete(txt, cur, groovyClassLoader, imports);
    } catch (Exception e) {
      return new AutocompleteResult(new ArrayList<>(), 0);
    }
  }

  private AutocompleteResult tryFindAutocomplete(String txt, int cur, ClassLoader l, Imports imports) {
    registry = AutocompleteRegistryFactory.createRegistry(cps);
    GroovyClassUtils cu = createClassUtils(l);
    setup(cu, registry);
    AutocompleteRegistryFactory.addDefaultImports(cu, registry, imports.toListOfStrings(), cps);
    AutocompleteRegistryFactory.moreSetup(cu);

    Lexer lexer = new GroovyLexer(new ANTLRInputStream(txt));
    lexer.removeErrorListeners();
    CommonTokenStream tokens = new CommonTokenStream(lexer);

    // Create a parser that reads from the scanner
    GroovyParser parser = new GroovyParser(tokens);
    parser.removeErrorListeners();

    // start parsing at the compilationUnit rule
    ParserRuleContext t = parser.compilationUnit();
    ParseTreeWalker walker = new ParseTreeWalker();
    List<AutocompleteCandidate> q = new ArrayList<>();

    GroovyImportDeclarationCompletion extractor = new GroovyImportDeclarationCompletion(txt, cur, registry, cps, cu);
    GroovyNameBuilder extractor2 = new GroovyNameBuilder(registry, cu);
    GroovyNodeCompletion extractor3 = new GroovyNodeCompletion(txt, cur, registry, cu);
    GroovyReflectionCompletion extractor4 = new GroovyReflectionCompletion(scriptBinding, groovyClassLoader, imports);

    walker.walk(extractor, t);
    if (extractor.getQuery() != null)
      q.addAll(extractor.getQuery());
    walker.walk(extractor2, t);
    walker.walk(extractor3, t);
    if (extractor3.getQuery() != null)
      q.addAll(extractor3.getQuery());
    List<String> ret = registry.searchCandidates(q);
    
    int startIndex = getStartIndex(extractor, extractor2, extractor3);
    AutocompleteResult result = new AutocompleteResult(ret, startIndex);

    AutocompleteResult reflectionCompletions = extractor4.autocomplete(txt, cur);
    if(!reflectionCompletions.isEmpty())
      result.setStartIndex(reflectionCompletions.getStartIndex());
    
    result.append(reflectionCompletions);
    if (!result.isEmpty()) 
      return result;

    return findAutocompleteResult(txt, cur, cu);
  }

  private AutocompleteResult findAutocompleteResult(String txt, int cur, ClassUtils cu) {
    List<AutocompleteCandidate> q = new ArrayList<>();
    List<String> ret = new ArrayList<>();
    int startIndex = 0;
    for (int i = cur - 1; i >= 0; i--) {
      if (i < txt.length() && Character.isWhitespace(txt.charAt(i))) {
        String tx = txt.substring(i + 1, cur).trim();
        if (!txt.isEmpty()) {
          if (tx.contains(".")) {
            q.add(cu.expandExpression(tx, registry, cu.DO_ALL));
          } else {
            q.add(new AutocompleteCandidate(GroovyCompletionTypes.NAME, tx));
          }
          ret = registry.searchCandidates(q);
          startIndex = txt.substring(0, cur).lastIndexOf(tx) + tx.length();
        }
        break;
      }
    }
    return new AutocompleteResult(ret, startIndex);
  }

  private int getStartIndex(GroovyImportDeclarationCompletion extractor, GroovyNameBuilder extractor2, GroovyNodeCompletion extractor3) {
    if (extractor.getQuery() != null) {
      return extractor.getStartIndex();
    }
    if (extractor2.getQuery() != null) {
      return extractor2.getStartIndex();
    }
    if (extractor3.getQuery() != null) {
      return extractor3.getStartIndex();
    }
    return 0;
  }

  private GroovyClassUtils createClassUtils(ClassLoader l) {
    return new GroovyClassUtils(cps, l);
  }

}
