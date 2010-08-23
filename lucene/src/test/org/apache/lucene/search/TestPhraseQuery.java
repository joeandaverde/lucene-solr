package org.apache.lucene.search;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.store.*;
import org.apache.lucene.util.Version;
import org.apache.lucene.util._TestUtil;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;

/**
 * Tests {@link PhraseQuery}.
 *
 * @see TestPositionIncrement
 */
public class TestPhraseQuery extends LuceneTestCase {

  /** threshold for comparing floats */
  public static final float SCORE_COMP_THRESH = 1e-6f;
  
  private IndexSearcher searcher;
  private IndexReader reader;
  private PhraseQuery query;
  private Directory directory;
  private Random random;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    random = newRandom();
    directory = newDirectory(random);
    Analyzer analyzer = new Analyzer() {
      @Override
      public TokenStream tokenStream(String fieldName, Reader reader) {
        return new WhitespaceTokenizer(TEST_VERSION_CURRENT, reader);
      }

      @Override
      public int getPositionIncrementGap(String fieldName) {
        return 100;
      }
    };
    RandomIndexWriter writer = new RandomIndexWriter(random, directory, analyzer);
    
    Document doc = new Document();
    doc.add(new Field("field", "one two three four five", Field.Store.YES, Field.Index.ANALYZED));
    doc.add(new Field("repeated", "this is a repeated field - first part", Field.Store.YES, Field.Index.ANALYZED));
    Fieldable repeatedField = new Field("repeated", "second part of a repeated field", Field.Store.YES, Field.Index.ANALYZED);
    doc.add(repeatedField);
    doc.add(new Field("palindrome", "one two three two one", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);
    
    doc = new Document();
    doc.add(new Field("nonexist", "phrase exist notexist exist found", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);
    
    doc = new Document();
    doc.add(new Field("nonexist", "phrase exist notexist exist found", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);

    reader = writer.getReader();
    writer.close();

    searcher = new IndexSearcher(reader);
    query = new PhraseQuery();
  }

  @Override
  protected void tearDown() throws Exception {
    searcher.close();
    reader.close();
    directory.close();
    super.tearDown();
  }

  public void testNotCloseEnough() throws Exception {
    query.setSlop(2);
    query.add(new Term("field", "one"));
    query.add(new Term("field", "five"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals(0, hits.length);
    QueryUtils.check(query,searcher);
  }

  public void testBarelyCloseEnough() throws Exception {
    query.setSlop(3);
    query.add(new Term("field", "one"));
    query.add(new Term("field", "five"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals(1, hits.length);
    QueryUtils.check(query,searcher);
  }

  /**
   * Ensures slop of 0 works for exact matches, but not reversed
   */
  public void testExact() throws Exception {
    // slop is zero by default
    query.add(new Term("field", "four"));
    query.add(new Term("field", "five"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("exact match", 1, hits.length);
    QueryUtils.check(query,searcher);


    query = new PhraseQuery();
    query.add(new Term("field", "two"));
    query.add(new Term("field", "one"));
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("reverse not exact", 0, hits.length);
    QueryUtils.check(query,searcher);
  }

  public void testSlop1() throws Exception {
    // Ensures slop of 1 works with terms in order.
    query.setSlop(1);
    query.add(new Term("field", "one"));
    query.add(new Term("field", "two"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("in order", 1, hits.length);
    QueryUtils.check(query,searcher);


    // Ensures slop of 1 does not work for phrases out of order;
    // must be at least 2.
    query = new PhraseQuery();
    query.setSlop(1);
    query.add(new Term("field", "two"));
    query.add(new Term("field", "one"));
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("reversed, slop not 2 or more", 0, hits.length);
    QueryUtils.check(query,searcher);
  }

  /**
   * As long as slop is at least 2, terms can be reversed
   */
  public void testOrderDoesntMatter() throws Exception {
    query.setSlop(2); // must be at least two for reverse order match
    query.add(new Term("field", "two"));
    query.add(new Term("field", "one"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("just sloppy enough", 1, hits.length);
    QueryUtils.check(query,searcher);


    query = new PhraseQuery();
    query.setSlop(2);
    query.add(new Term("field", "three"));
    query.add(new Term("field", "one"));
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("not sloppy enough", 0, hits.length);
    QueryUtils.check(query,searcher);

  }

  /**
   * slop is the total number of positional moves allowed
   * to line up a phrase
   */
  public void testMulipleTerms() throws Exception {
    query.setSlop(2);
    query.add(new Term("field", "one"));
    query.add(new Term("field", "three"));
    query.add(new Term("field", "five"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("two total moves", 1, hits.length);
    QueryUtils.check(query,searcher);


    query = new PhraseQuery();
    query.setSlop(5); // it takes six moves to match this phrase
    query.add(new Term("field", "five"));
    query.add(new Term("field", "three"));
    query.add(new Term("field", "one"));
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("slop of 5 not close enough", 0, hits.length);
    QueryUtils.check(query,searcher);


    query.setSlop(6);
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("slop of 6 just right", 1, hits.length);
    QueryUtils.check(query,searcher);

  }
  
  public void testPhraseQueryWithStopAnalyzer() throws Exception {
    Directory directory = newDirectory(random);
    StopAnalyzer stopAnalyzer = new StopAnalyzer(Version.LUCENE_24);
    RandomIndexWriter writer = new RandomIndexWriter(random, directory, 
        newIndexWriterConfig(random, Version.LUCENE_24, stopAnalyzer));
    Document doc = new Document();
    doc.add(new Field("field", "the stop words are here", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);
    IndexReader reader = writer.getReader();
    writer.close();

    IndexSearcher searcher = new IndexSearcher(reader);

    // valid exact phrase query
    PhraseQuery query = new PhraseQuery();
    query.add(new Term("field","stop"));
    query.add(new Term("field","words"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals(1, hits.length);
    QueryUtils.check(query,searcher);


    // StopAnalyzer as of 2.4 does not leave "holes", so this matches.
    query = new PhraseQuery();
    query.add(new Term("field", "words"));
    query.add(new Term("field", "here"));
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals(1, hits.length);
    QueryUtils.check(query,searcher);


    searcher.close();
    reader.close();
    directory.close();
  }
  
  public void testPhraseQueryInConjunctionScorer() throws Exception {
    Directory directory = newDirectory(random);
    RandomIndexWriter writer = new RandomIndexWriter(random, directory);
    
    Document doc = new Document();
    doc.add(new Field("source", "marketing info", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);
    
    doc = new Document();
    doc.add(new Field("contents", "foobar", Field.Store.YES, Field.Index.ANALYZED));
    doc.add(new Field("source", "marketing info", Field.Store.YES, Field.Index.ANALYZED)); 
    writer.addDocument(doc);
    
    IndexReader reader = writer.getReader();
    writer.close();
    
    IndexSearcher searcher = new IndexSearcher(reader);
    
    PhraseQuery phraseQuery = new PhraseQuery();
    phraseQuery.add(new Term("source", "marketing"));
    phraseQuery.add(new Term("source", "info"));
    ScoreDoc[] hits = searcher.search(phraseQuery, null, 1000).scoreDocs;
    assertEquals(2, hits.length);
    QueryUtils.check(phraseQuery,searcher);

    
    TermQuery termQuery = new TermQuery(new Term("contents","foobar"));
    BooleanQuery booleanQuery = new BooleanQuery();
    booleanQuery.add(termQuery, BooleanClause.Occur.MUST);
    booleanQuery.add(phraseQuery, BooleanClause.Occur.MUST);
    hits = searcher.search(booleanQuery, null, 1000).scoreDocs;
    assertEquals(1, hits.length);
    QueryUtils.check(termQuery,searcher);

    
    searcher.close();
    reader.close();
    
    writer = new RandomIndexWriter(random, directory, 
        newIndexWriterConfig(random, TEST_VERSION_CURRENT, new WhitespaceAnalyzer(TEST_VERSION_CURRENT)).setOpenMode(OpenMode.CREATE));
    doc = new Document();
    doc.add(new Field("contents", "map entry woo", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);

    doc = new Document();
    doc.add(new Field("contents", "woo map entry", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);

    doc = new Document();
    doc.add(new Field("contents", "map foobarword entry woo", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);

    reader = writer.getReader();
    writer.close();
    
    searcher = new IndexSearcher(reader);
    
    termQuery = new TermQuery(new Term("contents","woo"));
    phraseQuery = new PhraseQuery();
    phraseQuery.add(new Term("contents","map"));
    phraseQuery.add(new Term("contents","entry"));
    
    hits = searcher.search(termQuery, null, 1000).scoreDocs;
    assertEquals(3, hits.length);
    hits = searcher.search(phraseQuery, null, 1000).scoreDocs;
    assertEquals(2, hits.length);

    
    booleanQuery = new BooleanQuery();
    booleanQuery.add(termQuery, BooleanClause.Occur.MUST);
    booleanQuery.add(phraseQuery, BooleanClause.Occur.MUST);
    hits = searcher.search(booleanQuery, null, 1000).scoreDocs;
    assertEquals(2, hits.length);
    
    booleanQuery = new BooleanQuery();
    booleanQuery.add(phraseQuery, BooleanClause.Occur.MUST);
    booleanQuery.add(termQuery, BooleanClause.Occur.MUST);
    hits = searcher.search(booleanQuery, null, 1000).scoreDocs;
    assertEquals(2, hits.length);
    QueryUtils.check(booleanQuery,searcher);

    
    searcher.close();
    reader.close();
    directory.close();
  }
  
  public void testSlopScoring() throws IOException {
    Directory directory = newDirectory(random);
    RandomIndexWriter writer = new RandomIndexWriter(random, directory);

    Document doc = new Document();
    doc.add(new Field("field", "foo firstname lastname foo", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc);
    
    Document doc2 = new Document();
    doc2.add(new Field("field", "foo firstname zzz lastname foo", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc2);
    
    Document doc3 = new Document();
    doc3.add(new Field("field", "foo firstname zzz yyy lastname foo", Field.Store.YES, Field.Index.ANALYZED));
    writer.addDocument(doc3);
    
    IndexReader reader = writer.getReader();
    writer.close();

    Searcher searcher = new IndexSearcher(reader);
    PhraseQuery query = new PhraseQuery();
    query.add(new Term("field", "firstname"));
    query.add(new Term("field", "lastname"));
    query.setSlop(Integer.MAX_VALUE);
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals(3, hits.length);
    // Make sure that those matches where the terms appear closer to
    // each other get a higher score:
    assertEquals(0.71, hits[0].score, 0.01);
    assertEquals(0, hits[0].doc);
    assertEquals(0.44, hits[1].score, 0.01);
    assertEquals(1, hits[1].doc);
    assertEquals(0.31, hits[2].score, 0.01);
    assertEquals(2, hits[2].doc);
    QueryUtils.check(query,searcher);
    searcher.close();
    reader.close();
    directory.close();
  }
  
  public void testToString() throws Exception {
    StopAnalyzer analyzer = new StopAnalyzer(TEST_VERSION_CURRENT);
    QueryParser qp = new QueryParser(TEST_VERSION_CURRENT, "field", analyzer);
    qp.setEnablePositionIncrements(true);
    PhraseQuery q = (PhraseQuery)qp.parse("\"this hi this is a test is\"");
    assertEquals("field:\"? hi ? ? ? test\"", q.toString());
    q.add(new Term("field", "hello"), 1);
    assertEquals("field:\"? hi|hello ? ? ? test\"", q.toString());
  }

  public void testWrappedPhrase() throws IOException {
    query.add(new Term("repeated", "first"));
    query.add(new Term("repeated", "part"));
    query.add(new Term("repeated", "second"));
    query.add(new Term("repeated", "part"));
    query.setSlop(100);

    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("slop of 100 just right", 1, hits.length);
    QueryUtils.check(query,searcher);

    query.setSlop(99);

    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("slop of 99 not enough", 0, hits.length);
    QueryUtils.check(query,searcher);
  }

  // work on two docs like this: "phrase exist notexist exist found"
  public void testNonExistingPhrase() throws IOException {
    // phrase without repetitions that exists in 2 docs
    query.add(new Term("nonexist", "phrase"));
    query.add(new Term("nonexist", "notexist"));
    query.add(new Term("nonexist", "found"));
    query.setSlop(2); // would be found this way

    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("phrase without repetitions exists in 2 docs", 2, hits.length);
    QueryUtils.check(query,searcher);

    // phrase with repetitions that exists in 2 docs
    query = new PhraseQuery();
    query.add(new Term("nonexist", "phrase"));
    query.add(new Term("nonexist", "exist"));
    query.add(new Term("nonexist", "exist"));
    query.setSlop(1); // would be found 

    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("phrase with repetitions exists in two docs", 2, hits.length);
    QueryUtils.check(query,searcher);

    // phrase I with repetitions that does not exist in any doc
    query = new PhraseQuery();
    query.add(new Term("nonexist", "phrase"));
    query.add(new Term("nonexist", "notexist"));
    query.add(new Term("nonexist", "phrase"));
    query.setSlop(1000); // would not be found no matter how high the slop is

    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("nonexisting phrase with repetitions does not exist in any doc", 0, hits.length);
    QueryUtils.check(query,searcher);

    // phrase II with repetitions that does not exist in any doc
    query = new PhraseQuery();
    query.add(new Term("nonexist", "phrase"));
    query.add(new Term("nonexist", "exist"));
    query.add(new Term("nonexist", "exist"));
    query.add(new Term("nonexist", "exist"));
    query.setSlop(1000); // would not be found no matter how high the slop is

    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("nonexisting phrase with repetitions does not exist in any doc", 0, hits.length);
    QueryUtils.check(query,searcher);

  }

  /**
   * Working on a 2 fields like this:
   *    Field("field", "one two three four five")
   *    Field("palindrome", "one two three two one")
   * Phrase of size 2 occuriong twice, once in order and once in reverse, 
   * because doc is a palyndrome, is counted twice. 
   * Also, in this case order in query does not matter. 
   * Also, when an exact match is found, both sloppy scorer and exact scorer scores the same.   
   */
  public void testPalyndrome2() throws Exception {
    
    // search on non palyndrome, find phrase with no slop, using exact phrase scorer
    query.setSlop(0); // to use exact phrase scorer
    query.add(new Term("field", "two"));
    query.add(new Term("field", "three"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("phrase found with exact phrase scorer", 1, hits.length);
    float score0 = hits[0].score;
    //System.out.println("(exact) field: two three: "+score0);
    QueryUtils.check(query,searcher);

    // search on non palyndrome, find phrase with slop 2, though no slop required here.
    query.setSlop(2); // to use sloppy scorer 
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("just sloppy enough", 1, hits.length);
    float score1 = hits[0].score;
    //System.out.println("(sloppy) field: two three: "+score1);
    assertEquals("exact scorer and sloppy scorer score the same when slop does not matter",score0, score1, SCORE_COMP_THRESH);
    QueryUtils.check(query,searcher);

    // search ordered in palyndrome, find it twice
    query = new PhraseQuery();
    query.setSlop(2); // must be at least two for both ordered and reversed to match
    query.add(new Term("palindrome", "two"));
    query.add(new Term("palindrome", "three"));
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("just sloppy enough", 1, hits.length);
    //float score2 = hits[0].score;
    //System.out.println("palindrome: two three: "+score2);
    QueryUtils.check(query,searcher);
    
    //commented out for sloppy-phrase efficiency (issue 736) - see SloppyPhraseScorer.phraseFreq(). 
    //assertTrue("ordered scores higher in palindrome",score1+SCORE_COMP_THRESH<score2);

    // search reveresed in palyndrome, find it twice
    query = new PhraseQuery();
    query.setSlop(2); // must be at least two for both ordered and reversed to match
    query.add(new Term("palindrome", "three"));
    query.add(new Term("palindrome", "two"));
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("just sloppy enough", 1, hits.length);
    //float score3 = hits[0].score;
    //System.out.println("palindrome: three two: "+score3);
    QueryUtils.check(query,searcher);

    //commented out for sloppy-phrase efficiency (issue 736) - see SloppyPhraseScorer.phraseFreq(). 
    //assertTrue("reversed scores higher in palindrome",score1+SCORE_COMP_THRESH<score3);
    //assertEquals("ordered or reversed does not matter",score2, score3, SCORE_COMP_THRESH);
  }

  /**
   * Working on a 2 fields like this:
   *    Field("field", "one two three four five")
   *    Field("palindrome", "one two three two one")
   * Phrase of size 3 occuriong twice, once in order and once in reverse, 
   * because doc is a palyndrome, is counted twice. 
   * Also, in this case order in query does not matter. 
   * Also, when an exact match is found, both sloppy scorer and exact scorer scores the same.   
   */
  public void testPalyndrome3() throws Exception {
    
    // search on non palyndrome, find phrase with no slop, using exact phrase scorer
    query.setSlop(0); // to use exact phrase scorer
    query.add(new Term("field", "one"));
    query.add(new Term("field", "two"));
    query.add(new Term("field", "three"));
    ScoreDoc[] hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("phrase found with exact phrase scorer", 1, hits.length);
    float score0 = hits[0].score;
    //System.out.println("(exact) field: one two three: "+score0);
    QueryUtils.check(query,searcher);

    // just make sure no exc:
    searcher.explain(query, 0);

    // search on non palyndrome, find phrase with slop 3, though no slop required here.
    query.setSlop(4); // to use sloppy scorer 
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("just sloppy enough", 1, hits.length);
    float score1 = hits[0].score;
    //System.out.println("(sloppy) field: one two three: "+score1);
    assertEquals("exact scorer and sloppy scorer score the same when slop does not matter",score0, score1, SCORE_COMP_THRESH);
    QueryUtils.check(query,searcher);

    // search ordered in palyndrome, find it twice
    query = new PhraseQuery();
    query.setSlop(4); // must be at least four for both ordered and reversed to match
    query.add(new Term("palindrome", "one"));
    query.add(new Term("palindrome", "two"));
    query.add(new Term("palindrome", "three"));
    hits = searcher.search(query, null, 1000).scoreDocs;

    // just make sure no exc:
    searcher.explain(query, 0);

    assertEquals("just sloppy enough", 1, hits.length);
    //float score2 = hits[0].score;
    //System.out.println("palindrome: one two three: "+score2);
    QueryUtils.check(query,searcher);
    
    //commented out for sloppy-phrase efficiency (issue 736) - see SloppyPhraseScorer.phraseFreq(). 
    //assertTrue("ordered scores higher in palindrome",score1+SCORE_COMP_THRESH<score2);

    // search reveresed in palyndrome, find it twice
    query = new PhraseQuery();
    query.setSlop(4); // must be at least four for both ordered and reversed to match
    query.add(new Term("palindrome", "three"));
    query.add(new Term("palindrome", "two"));
    query.add(new Term("palindrome", "one"));
    hits = searcher.search(query, null, 1000).scoreDocs;
    assertEquals("just sloppy enough", 1, hits.length);
    //float score3 = hits[0].score;
    //System.out.println("palindrome: three two one: "+score3);
    QueryUtils.check(query,searcher);

    //commented out for sloppy-phrase efficiency (issue 736) - see SloppyPhraseScorer.phraseFreq(). 
    //assertTrue("reversed scores higher in palindrome",score1+SCORE_COMP_THRESH<score3);
    //assertEquals("ordered or reversed does not matter",score2, score3, SCORE_COMP_THRESH);
  }

  // LUCENE-1280
  public void testEmptyPhraseQuery() throws Throwable {
    final BooleanQuery q2 = new BooleanQuery();
    q2.add(new PhraseQuery(), BooleanClause.Occur.MUST);
    q2.toString();
  }
  
  /* test that a single term is rewritten to a term query */
  public void testRewrite() throws IOException {
    PhraseQuery pq = new PhraseQuery();
    pq.add(new Term("foo", "bar"));
    Query rewritten = pq.rewrite(searcher.getIndexReader());
    assertTrue(rewritten instanceof TermQuery);
  }

  public void testRandomPhrases() throws Exception {
    Directory dir = newDirectory(random);
    Analyzer analyzer = new WhitespaceAnalyzer();

    RandomIndexWriter w  = new RandomIndexWriter(random, dir, analyzer);
    List<List<String>> docs = new ArrayList<List<String>>();
    Document d = new Document();
    Field f = new Field("f", "", Field.Store.NO, Field.Index.ANALYZED);
    d.add(f);

    Random r = random;

    int NUM_DOCS = 10 * RANDOM_MULTIPLIER;
    for (int i = 0; i < NUM_DOCS; i++) {
      // must be > 4096 so it spans multiple chunks
      int termCount = _TestUtil.nextInt(r, 10000, 30000);

      List<String> doc = new ArrayList<String>();

      StringBuilder sb = new StringBuilder();
      while(doc.size() < termCount) {
        if (r.nextInt(5) == 1 || docs.size() == 0) {
          // make new non-empty-string term
          String term;
          while(true) {
            term = _TestUtil.randomUnicodeString(r);
            if (term.length() > 0) {
              break;
            }
          }
          TokenStream ts = analyzer.reusableTokenStream("ignore", new StringReader(term));
          CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
          while(ts.incrementToken()) {
            String text = termAttr.toString();
            doc.add(text);
            sb.append(text).append(' ');
          }
        } else {
          // pick existing sub-phrase
          List<String> lastDoc = docs.get(r.nextInt(docs.size()));
          int len = _TestUtil.nextInt(r, 1, 10);
          int start = r.nextInt(lastDoc.size()-len);
          for(int k=start;k<start+len;k++) {
            String t = lastDoc.get(k);
            doc.add(t);
            sb.append(t).append(' ');
          }
        }
      }
      docs.add(doc);
      f.setValue(sb.toString());
      w.addDocument(d);
    }

    IndexReader reader = w.getReader();
    IndexSearcher s = new IndexSearcher(reader);
    w.close();

    // now search
    int num = 100 * RANDOM_MULTIPLIER;
    for(int i=0;i<num;i++) {
      int docID = r.nextInt(docs.size());
      List<String> doc = docs.get(docID);
      
      final int numTerm = _TestUtil.nextInt(r, 2, 20);
      final int start = r.nextInt(doc.size()-numTerm);
      PhraseQuery pq = new PhraseQuery();
      StringBuilder sb = new StringBuilder();
      for(int t=start;t<start+numTerm;t++) {
        pq.add(new Term("f", doc.get(t)));
        sb.append(doc.get(t)).append(' ');
      }

      TopDocs hits = s.search(pq, NUM_DOCS);
      boolean found = false;
      for(int j=0;j<hits.scoreDocs.length;j++) {
        if (hits.scoreDocs[j].doc == docID) {
          found = true;
          break;
        }
      }

      assertTrue("phrase '" + sb + "' not found; start=" + start, found);
    }

    reader.close();
    s.close();
    dir.close();
  }
}
