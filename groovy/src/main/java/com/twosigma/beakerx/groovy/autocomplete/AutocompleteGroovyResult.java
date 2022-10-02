/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
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

import com.twosigma.beakerx.autocomplete.AutocompleteResult;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;

public class AutocompleteGroovyResult extends AutocompleteResult {

  public AutocompleteGroovyResult(List<String> matches, int startIndex, List<String> typeInfos) {
    super(matches, startIndex, typeInfos);
  }

  public AutocompleteGroovyResult(List<String> matches, int startIndex) {
    super(matches, startIndex);
  }

  public static int getStartIndex(ParserRuleContext ctx) {
    return ctx.getStop().getStartIndex();
  }
}
