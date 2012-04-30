/*
   Copyright 2010-2012 Alexey Skorokhodov.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package org.redmine.ta.internal;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.exolab.castor.mapping.Mapping;
import org.exolab.castor.mapping.MappingException;
import org.exolab.castor.xml.Unmarshaller;
import org.redmine.ta.beans.Attachment;
import org.redmine.ta.beans.Issue;
import org.redmine.ta.beans.IssueCategory;
import org.redmine.ta.beans.IssueRelation;
import org.redmine.ta.beans.IssueStatus;
import org.redmine.ta.beans.News;
import org.redmine.ta.beans.Project;
import org.redmine.ta.beans.SavedQuery;
import org.redmine.ta.beans.TimeEntry;
import org.redmine.ta.beans.Tracker;
import org.redmine.ta.beans.User;
import org.redmine.ta.beans.Version;
import org.xml.sax.InputSource;

public class RedmineXMLParser {

    private static final int UNKNOWN = -1;
    private static final String MAPPING_PROJECTS_LIST = "/mapping_projects_list.xml";
    private static final String MAPPING_ISSUES = "/mapping_issues_list.xml";
    private static final String MAPPING_USERS = "/mapping_users.xml";
    private static final String MAPPING_STATUSES = "/mapping_statuses_list.xml";
    private static final String MAPPING_VERSIONS = "/mapping_versions_list.xml";
    private static final String MAPPING_CATEGORIES = "/mapping_categories_list.xml";
    private static final String MAPPING_TRACKERS = "/mapping_trackers_list.xml";
    private static final String MAPPING_ATTACHMENTS = "/mapping_attachments_list.xml";
    private static final String MAPPING_NEWS = "/mapping_news_list.xml";

    // TODO optimize : pre-load xml
    private static final Map<Class<?>, String> fromRedmineMap = new HashMap<Class<?>, String>();
    private static final Map<Class<?>, Collection<Pattern>> badPatterns = new HashMap<Class<?>, Collection<Pattern>>();

    static {
        fromRedmineMap.put(User.class, MAPPING_USERS);
        fromRedmineMap.put(Issue.class, MAPPING_ISSUES);
        fromRedmineMap.put(Project.class, MAPPING_PROJECTS_LIST);
        fromRedmineMap.put(TimeEntry.class, "/mapping_time_entries.xml");
        fromRedmineMap.put(SavedQuery.class, "/mapping_queries.xml");
        fromRedmineMap.put(IssueRelation.class, "/mapping_relations.xml");
        fromRedmineMap.put(IssueStatus.class, MAPPING_STATUSES);
        fromRedmineMap.put(Version.class, MAPPING_VERSIONS);
        fromRedmineMap.put(IssueCategory.class, MAPPING_CATEGORIES);
        fromRedmineMap.put(Tracker.class, MAPPING_TRACKERS);
        fromRedmineMap.put(Attachment.class, MAPPING_ATTACHMENTS);
        fromRedmineMap.put(News.class, MAPPING_NEWS);

        // see bug https://www.hostedredmine.com/issues/8240
        badPatterns.put(Issue.class, Arrays.asList(
      				Pattern.compile(Pattern.quote("<estimated_hours></estimated_hours>")),
      				Pattern.compile(Pattern.quote("<estimated_hours/>"))));

    }

    public static Project parseProjectFromXML(String xml)
            throws RuntimeException {
        return parseObjectFromXML(Project.class, xml);
    }

    private static String removeBadTags(Class<?> redmineClass, String xml) {
    	final Collection<Pattern> patterns = badPatterns.get(redmineClass);
    	if (patterns == null) {
    		return xml;
    	}
        String newXML = xml;
    	for (Pattern pattern : patterns) {
    		newXML = pattern.matcher(newXML).replaceAll("");
    	}
    	return newXML;
    }

    /**
     * XML contains this line near the top:
     * <pre>
     * &lt;?xml version="1.0" encoding="UTF-8"?>&lt;issues type="array" limit="25" total_count="103" offset="0">
     * &lt;?xml version="1.0" encoding="UTF-8"?>&lt;projects type="array" total_count="84" limit="25" offset="0">
     * </pre>
     * <p>need to parse "total_count" value
     *
     * @return -1 (UNKNOWN) if can't parse - which means that the string is
     *         invalid / generated by an old Redmine version
     */
    public static int parseObjectsTotalCount(String objectsXML) {
        String reg = "<\\?xml version=\"1.0\" encoding=\"UTF-8\"\\?><.+ .*total_count=\"";
        int maxCharsToCheck = Math.min(200, objectsXML.length());

        String first200Chars = objectsXML.substring(0, maxCharsToCheck);
//		String reg = "<\\?xml version=\"1.0\" encoding=\"UTF-8\"\\?><.+ type=\"array\".*total_count=\"";
        Pattern pattern = Pattern.compile(reg);
        Matcher matcher = pattern.matcher(first200Chars);
        int result = UNKNOWN;
        if (matcher.find()) {

            int indexBeginNumber = matcher.end();

            String tmp1 = first200Chars.substring(indexBeginNumber);
            int end = tmp1.indexOf('"');
            String numStr = tmp1.substring(0, end);
            result = Integer.parseInt(numStr);
        }
        return result;

    }

    public static List<Project> parseProjectsFromXML(String xml) {
        return parseObjectsFromXML(Project.class, xml);
    }

    private static Unmarshaller getUnmarshaller(String configFile,
                                                Class<?> classToUse) {
//		String configFile = configFilesMap.get(classToUse);
        InputSource inputSource = new InputSource(
                RedmineXMLParser.class.getResourceAsStream(configFile));
        ClassLoader cl = RedmineXMLParser.class.getClassLoader();
        // Note: Castor XML is packed in a separate OSGI bundle, so
        // must set the classloader so that Castor will see our classes
        Mapping mapping = new Mapping(cl);
        mapping.loadMapping(inputSource);

        Unmarshaller unmarshaller;
        try {
            unmarshaller = new Unmarshaller(mapping);
        } catch (MappingException e) {
            throw new RuntimeException(e);
        }
        unmarshaller.setClass(classToUse);
        unmarshaller.setWhitespacePreserve(true);
        return unmarshaller;
    }

    /**
     * @throws RuntimeException if the text does not start with a valid XML tag.
     */
    static void verifyStartsAsXML(String text) {
        String XML_START_PATTERN = "<?xml version=";
        String lines[] = text.split("\\r?\\n");
        if ((lines.length == 0) || !lines[0].startsWith(XML_START_PATTERN)) {
            // show not more than 500 chars
            int charsToShow = text.length() < 500 ? text.length() : 500;
            throw new RuntimeException(
                    "RedmineXMLParser: can't parse the response. This is not a valid XML:\n\n"
                            + text.substring(0, charsToShow) + "...");
        }

    }

    private static <T> T unmarshal(Class<?> elementClass, String body,
   			Class<T> resultClass) {
           verifyStartsAsXML(body);
           body = removeBadTags(elementClass, body);

           String configFile = fromRedmineMap.get(elementClass);
           Unmarshaller unmarshaller = getUnmarshaller(configFile, resultClass);

           StringReader reader = null;
           try {
               reader = new StringReader(body);
               return resultClass.cast(unmarshaller.unmarshal(reader));
           } catch (Exception e) {
               throw new RuntimeException(e);
           } finally {
               if (reader != null) {
                   reader.close();
               }
           }
       }

	public static <T> List<T> parseObjectsFromXML(Class<T> classs, String body)	{
    	return unmarshal(classs, body, ArrayList.class);
    }

	public static <T> T parseObjectFromXML(Class<T> classs, String xml) {
    	return unmarshal(classs, xml, classs);
    }

    public static List<User> parseUsersFromXML(String body) {
        return parseObjectsFromXML(User.class, body);
    }

    public static User parseUserFromXML(String body) {
        return parseObjectFromXML(User.class, body);
    }

    /**
     * @param responseBody  sample parameter:
     * <pre>
     * 	&lt;?xml version="1.0" encoding="UTF-8"?>
     * 	&lt;errors>
     * 		&lt;error>Name can't be blank&lt;/error>
     * 		&lt;error>Identifier has already been taken&lt;/error>
     * 	&lt;/errors>
     * </pre>
     */
    public static List<String> parseErrors(String responseBody) {
        List<String> errors = new ArrayList<String>();
        /* I don't want to use Castor XML here with all these "include mapping" for errors file
          * and making sure the mapping files are accessible in a plugin/jar/classpath and so on */
        String lines[] = responseBody.split("\\r?\\n");
        // skip first two lines: xml declaration and <errors> tag
        int lineToStartWith = 2;
        // skip last line with </errors> tag
        int lastLine = lines.length - 1;
        String openTag = "<error>";
        String closeTag = "</error>";
        for (int i = lineToStartWith; i < lastLine; i++) {
            int begin = lines[i].indexOf(openTag) + openTag.length();
            int end = lines[i].indexOf(closeTag);
            if (begin >= 0 && end >= 0) {
                errors.add(lines[i].substring(begin, end));
            }
        }
        return errors;
    }

    public static List<TimeEntry> parseTimeEntries(String xml) {
        return parseObjectsFromXML(TimeEntry.class, xml);
    }

    public static IssueRelation parseRelationFromXML(String body) {
        return parseObjectFromXML(IssueRelation.class, body);
    }

    public static List<Version> parseVersionsFromXML(String body) {
        return parseObjectsFromXML(Version.class, body);
    }

    public static Version parseVersionFromXML(String body) {
        return parseObjectFromXML(Version.class, body);
    }

    public static List<IssueCategory> parseIssueCategoriesFromXML(String body) {
        return parseObjectsFromXML(IssueCategory.class, body);
    }

}
