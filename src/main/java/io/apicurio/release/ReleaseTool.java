/*
 * Copyright 2020 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.release;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

/**
 * @author eric.wittmann@gmail.com
 */
public class ReleaseTool {

    /**
     * Main method.
     * @param args
     */
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("r", "repository", true, "The name of the repository being released.");
        options.addOption("n", "release-name", true, "The name of the new release.");
        options.addOption("p", "prerelease", false, "Indicate that this is a pre-release.");
        options.addOption("t", "release-tag", true, "The tag name of the new release.");
        options.addOption("o", "previous-tag", true, "The tag name of the previous release.");
        options.addOption("g", "github-pat", true, "The GitHub PAT (for authentication/authorization).");
        options.addOption("a", "artifact", true, "The binary release artifact (full path).");
        options.addOption("d", "output-directory", true, "Where to store output file(s).");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (    !cmd.hasOption("r") ||
                !cmd.hasOption("n") ||
                !cmd.hasOption("t") ||
                !cmd.hasOption("o") ||
                !cmd.hasOption("g") )
        {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "release-tool", options );
            System.exit(1);
        }
        
        try {
            ReleaseTool tool = new ReleaseTool(cmd);
            tool.release();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private String org = "apicurio";
    private String repository;
    private String releaseName;
    private boolean isPrerelease;
    private String releaseTag;
    private String oldReleaseTag;
    private String githubPAT;
    private String artifact;
    private File outputDir;
    
    /**
     * Constructor.
     * @param cmd
     */
    public ReleaseTool(CommandLine cmd) {
        // Arguments (from the command line)
        repository = cmd.getOptionValue("r");
        releaseName = cmd.getOptionValue("n");
        isPrerelease = cmd.hasOption("p");
        releaseTag = cmd.getOptionValue("t");
        oldReleaseTag = cmd.getOptionValue("o");
        githubPAT = cmd.getOptionValue("g");
        artifact = cmd.getOptionValue("a");
        outputDir = new File("");
        if (cmd.hasOption("d")) {
            outputDir = new File(cmd.getOptionValue("d"));
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
        }
    }

    /**
     * Do the release.
     * @throws Exception
     */
    private void release() throws Exception {
        if ("apicurio-studio".equals(repository)) {
            releaseStudio();
        } else if ("apicurito".equals(repository)) {
            releaseApicurito();
        } else {
            throw new Exception("Unsupported repository: " + repository);
        }
    }

    /**
     * Release the studio repo.
     */
    private void releaseStudio() throws Exception {
        if (artifact == null) {
            throw new Exception("Missing command line option: artifact (a)");
        }

        File releaseArtifactFile = new File(artifact);
        File releaseArtifactSigFile = new File(artifact + ".asc");

        String releaseArtifact = releaseArtifactFile.getName();
        String releaseArtifactSig = releaseArtifactSigFile.getName();

        if (!releaseArtifactFile.isFile()) {
            System.err.println("Missing file: " + releaseArtifactFile.getAbsolutePath());
            System.exit(1);
        }
        if (!releaseArtifactSigFile.isFile()) {
            System.err.println("Missing file: " + releaseArtifactSigFile.getAbsolutePath());
            System.exit(1);
        }

        System.out.println("=========================================");
        System.out.println("Releasing Apicurio Studio");
        System.out.println("Creating Release: " + releaseTag);
        System.out.println("Previous Release: " + oldReleaseTag);
        System.out.println("            Name: " + releaseName);
        System.out.println("        Artifact: " + releaseArtifact);
        System.out.println("     Pre-Release: " + isPrerelease);
        System.out.println("=========================================");

        String releaseNotes = "";

        // Step #1 - Generate Release Notes
        //   * Grab info about the previous release (extract publish date)
        //   * Query all Issues for ones closed since that date
        //   * Generate Release Notes from the resulting Issues
        try {
            List<JSONObject> issues = getIssuesForRelease(org, "apicurio-studio", "v" + oldReleaseTag, null, null);
            System.out.println("Found " + issues.size() + " issues closed in release " + releaseTag);

            String suffix = "For more information, please see the Apicurio Studio's official project site:\r\n" + 
                    "\r\n" + 
                    "* [General Information](http://www.apicur.io/)\r\n" + 
                    "* [Download/Quickstart](http://www.apicur.io/download)\r\n" + 
                    "* [Blog](http://www.apicur.io/blog)";
            releaseNotes = generateReleaseNotes(releaseName, releaseTag, issues, suffix);
            System.out.println("------------ Release Notes --------------");
            System.out.println(releaseNotes);
            System.out.println("-----------------------------------------");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        String assetUploadUrl = null;

        // Step #2 - Create a GitHub Release
        try {
            assetUploadUrl = createRelease(org, "apicurio-studio", releaseName, isPrerelease, "v" + releaseTag, releaseNotes);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        // Step #3 - Upload Release Artifact (zip file)
        System.out.println("\nUploading Quickstart Artifact: " + releaseArtifact);
        try {
            uploadReleaseArtifact(releaseArtifactFile, releaseArtifact, assetUploadUrl, "application/zip");
            Thread.sleep(1000);
            uploadReleaseArtifact(releaseArtifactSigFile, releaseArtifactSig, assetUploadUrl, "text/plain");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        
        Thread.sleep(1000);

        // Step #4 - Download Latest Release JSON for inclusion in the project web site
        try {
            System.out.println("Getting info about the release.");
            HttpResponse<JsonNode> response = Unirest.get("https://api.github.com/repos/apicurio/apicurio-studio/releases/latest")
                    .header("Accept", "application/json").asJson();
            if (response.getStatus() != 200) {
                throw new Exception("Failed to get release info: " + response.getStatusText());
            }
            JsonNode body = response.getBody();
            String publishedDate = body.getObject().getString("published_at");
            if (publishedDate == null) {
                throw new Exception("Could not find Published Date for release.");
            }
            String fname = publishedDate.replace(':', '-');
            File outFile = new File(outputDir, fname + ".json");
            
            System.out.println("Writing latest release info to: " + outFile.getAbsolutePath());
            
            String output = body.getObject().toString(4);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(output.getBytes("UTF-8"));
                fos.flush();
            }

            System.out.println("Release info successfully written.");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        
        System.out.println("=========================================");
        System.out.println("All Done!");
        System.out.println("=========================================");
    }

    /**
     * Release the apicurito repo.
     */
    private void releaseApicurito() throws Exception {
        System.out.println("=========================================");
        System.out.println("Releasing Apicurito");
        System.out.println("Creating Release: " + releaseTag);
        System.out.println("Previous Release: " + oldReleaseTag);
        System.out.println("            Name: " + releaseName);
        System.out.println("     Pre-Release: " + isPrerelease);
        System.out.println("=========================================");

        String releaseNotes = "";

        // Step #1 - Generate Release Notes
        //   * Grab info about the previous release (extract publish date)
        //   * Query all Issues for ones closed since that date
        //   * Generate Release Notes from the resulting Issues
        try {
            // Grab closed issues from Apicurito itself
            List<JSONObject> issues = getIssuesForRelease(org, "apicurito", oldReleaseTag, null, null);

            // Also grab issues from Apicurio Studio (editor only)
            String fromEditorVersion = getPackageDependencyVersion(org, "apicurito", "ui/package.json", oldReleaseTag, "apicurio-design-studio");
            String toEditorVersion = getPackageDependencyVersion(org, "apicurito", "ui/package.json", releaseTag, "apicurio-design-studio");
            if (!fromEditorVersion.equals(toEditorVersion)) {
                System.out.println("---");
                System.out.println("Apicurio editor upgraded from version " + fromEditorVersion + " to version "
                                + toEditorVersion + " - including studio editor issues in release notes.");
                System.out.println("---");
                String fromTag = "v" + fromEditorVersion + ".Final";
                String toTag = "v" + toEditorVersion + ".Final";
                List<JSONObject> editorIssues = getIssuesForRelease(org, "apicurio-studio", fromTag, toTag, Collections.singleton("editor"));
                issues.addAll(editorIssues);
            } else {
                System.out.println("---");
                System.out.println("No Apicurio editor version upgrade detected.  Version is: " + fromEditorVersion);
                System.out.println("---");
            }

            System.out.println("Found " + issues.size() + " issues closed in release " + releaseTag);

            String suffix = "";
            releaseNotes = generateReleaseNotes(releaseName, releaseTag, issues, suffix);
            System.out.println("------------ Release Notes --------------");
            System.out.println(releaseNotes);
            System.out.println("-----------------------------------------");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        // Step #2 - Create a GitHub Release
        try {
            createRelease(org, "apicurio-studio", releaseName, isPrerelease, releaseTag, releaseNotes);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("=========================================");
        System.out.println("All Done!");
        System.out.println("=========================================");        
    }

    /**
     * Gets the version of a dependency in a package.json file.
     * @param org
     * @param repo
     * @param path
     * @param tag
     * @param dependencyName
     * @throws Exception
     */
    private String getPackageDependencyVersion(String org, String repo, String path, String tag,
            String dependencyName) throws Exception {
        String contentUrl = "https://raw.githubusercontent.com/" + org + "/" + repo + "/" + tag + "/" + path;

        HttpResponse<JsonNode> response = Unirest.get(contentUrl).header("Accept", "*/*").asJson();
        if (response.getStatus() != 200) {
            throw new Exception("Failed to get release info: " + response.getStatusText());
        }
        JsonNode body = response.getBody();
        String version = body.getObject().getJSONObject("dependencies").getString(dependencyName);
        if (version == null) {
            throw new Exception("Could not find version info for dependency: " + dependencyName);
        }
        return version;
    }

    /**
     * @param releaseArtifactFile
     * @param releaseArtifact
     * @param assetUploadUrl
     * @throws Exception
     * @throws UnirestException
     */
    private void uploadReleaseArtifact(File releaseArtifactFile, String releaseArtifact,
            String assetUploadUrl, String assetContentType) throws Exception, UnirestException {
        String artifactUploadUrl = createUploadUrl(assetUploadUrl, releaseArtifact);
        byte [] artifactData = loadArtifactData(releaseArtifactFile);
        System.out.println("Uploading artifact asset: " + artifactUploadUrl);
        HttpResponse<JsonNode> response = Unirest.post(artifactUploadUrl)
                .header("Accept", "application/json")
                .header("Content-Type", assetContentType)
                .header("Authorization", "token " + githubPAT)
                .body(artifactData)
                .asJson();
        if (response.getStatus() != 201) {
            throw new Exception("Failed to upload asset: " + releaseArtifact, new Exception(response.getStatus() + "::" + response.getStatusText()));
        }
    }

    /**
     * @param org
     * @param repo
     * @param releaseName
     * @param isPrerelease
     * @param releaseTag
     * @param releaseNotes
     * @throws UnirestException
     * @throws Exception
     */
    private String createRelease(String org, String repo, String releaseName, boolean isPrerelease, String releaseTag,
            String releaseNotes) throws UnirestException, Exception {
        String assetUploadUrl;
        System.out.println("\nCreating GitHub Release " + releaseTag);
        JSONObject body = new JSONObject();
        body.put("tag_name", releaseTag);
        body.put("name", releaseName);
        body.put("body", releaseNotes);
        body.put("prerelease", isPrerelease);

        HttpResponse<JsonNode> response = Unirest.post("https://api.github.com/repos/" + org + "/" + repo + "/releases")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "token " + githubPAT)
                .body(body).asJson();
        if (response.getStatus() != 201) {
            System.out.println("!!! ERROR !!!");
            System.out.println("!!! " + response.getBody());
            throw new Exception("Failed to create release in GitHub: " + response.getStatusText());
        }

        assetUploadUrl = response.getBody().getObject().getString("upload_url");
        if (assetUploadUrl == null || assetUploadUrl.trim().isEmpty()) {
            throw new Exception("Failed to get Asset Upload URL for newly created release!");
        }
        return assetUploadUrl;
    }
    
    /**
     * Figures out the release/publish date for a release tag of a given repo.
     * @param org
     * @param repo
     * @param releaseTag
     * @param githubPAT
     * @return
     */
    private String getReleaseDate(String org, String repo, String releaseTag) throws Exception {
        System.out.println("Getting release data for " + org + "/" + repo + ":" + releaseTag);
        HttpResponse<JsonNode> response = Unirest.get("https://api.github.com/repos/" + org + "/" + repo + "/releases/tags/" + releaseTag)
                .header("Accept", "application/json").header("Authorization", "token " + githubPAT).asJson();
        if (response.getStatus() != 200) {
            throw new Exception("Failed to get release info: " + response.getStatusText());
        }
        JsonNode body = response.getBody();
        String publishedDate = body.getObject().getString("created_at");
        if (publishedDate == null) {
            throw new Exception("Could not find Published Date for release " + releaseTag);
        }
        System.out.println("Release " + releaseTag + " was published on " + publishedDate);
        return publishedDate;
    }

    /**
     * Generates the release notes for a release.
     * @param releaseName
     * @param releaseTag
     * @param issues
     */
    private String generateReleaseNotes(String releaseName, String releaseTag, List<JSONObject> issues, String suffix) {
        System.out.println("Generating Release Notes");

        StringBuilder builder = new StringBuilder();

        builder.append("This represents the official release of " + repoToName() + ", version ");
        builder.append(releaseTag);
        builder.append(".\n\n");
        builder.append("The following issues have been resolved in this release:\n\n");

        issues.forEach(issue -> {
            builder.append(String.format("* [#%d](%s) %s", issue.getInt("number"), issue.getString("html_url"), issue.getString("title")));
            builder.append("\n");
        });

        builder.append("\n\n");
        builder.append(suffix);

        return builder.toString();
    }

    /**
     * Returns all issues (as JSON nodes) that were closed between two releases.  If no "to" release tag is
     * given, then "now" is assumed.
     * @param org
     * @param repo
     * @param fromReleaseTag
     * @param toReleaseTag
     * @param githubPAT
     * @throws Exception
     */
    private List<JSONObject> getIssuesForRelease(String org, String repo, String fromReleaseTag,
            String toReleaseTag, final Set<String> requiredTags) throws Exception {
        List<JSONObject> rval = new ArrayList<>();
        
        final String from = getReleaseDate(org, repo, fromReleaseTag);
        final String to = toReleaseTag == null ? null : getReleaseDate(org, repo, toReleaseTag);

        String currentPageUrl = "https://api.github.com/repos/" + org + "/" + repo + "/issues";
        int pageNum = 1;
        while (currentPageUrl != null) {
            System.out.println("Querying page " + pageNum + " of issues.");
            HttpResponse<JsonNode> response = Unirest.get(currentPageUrl)
                    .queryString("since", from)
                    .queryString("state", "closed")
                    .header("Accept", "application/json")
                    .header("Authorization", "token " + githubPAT).asJson();
            if (response.getStatus() != 200) {
                throw new Exception("Failed to list Issues: " + response.getStatusText());
            }
            JSONArray issueNodes = response.getBody().getArray();
            issueNodes.forEach(issueNode -> {
                JSONObject issue = (JSONObject) issueNode;
                String closedOn = issue.getString("closed_at");
                if (from.compareTo(closedOn) < 0 && (to == null || (to != null && to.compareTo(closedOn) > 0))) {
                    if (!isIssueExcluded(issue, requiredTags)) {
                        rval.add(issue);
                    } else {
                        System.out.println("Skipping issue (excluded): " + issue.getString("title"));
                    }
                } else {
                    System.out.println("Skipping issue (old release): " + issue.getString("title"));
                }
            });

            System.out.println("Processing page " + pageNum + " of issues.");
            System.out.println("    Found " + issueNodes.length() + " issues on page.");
            String allLinks = response.getHeaders().getFirst("Link");
            Map<String, Link> links = Link.parseAll(allLinks);
            if (links.containsKey("next")) {
                currentPageUrl = links.get("next").getUrl();
            } else {
                currentPageUrl = null;
            }
            pageNum++;
        }

        return rval;
    }

    /**
     * Tests whether an issue should be excluded from the release notes based on 
     * certain labels the issue might have (e.g. dependabot issues).
     * @param issueNode
     * @param requiredTags 
     */
    private boolean isIssueExcluded(JSONObject issueNode, Set<String> requiredTags) {
        if (requiredTags == null) {
            requiredTags = Collections.emptySet();
        }

        JSONArray labelsArray = issueNode.getJSONArray("labels");
        if (labelsArray != null) {
            Set<String> labels = labelsArray.toList().stream().map( label -> {
                return ((Map<?,?>) label).get("name").toString();
            }).collect(Collectors.toSet());
            // Exclude the issue if it contains any of the following tags.
            if (labels.contains("dependencies") || labels.contains("question") || labels.contains("invalid")  
                    || labels.contains("wontfix")|| labels.contains("duplicate")) {
                return true;
            }
            // Exclude the issue if it does NOT contain **ALL** of the required tags.
            for (String requiredTag : requiredTags) {
                if (!labels.contains(requiredTag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param assetUploadUrl
     * @param assetName
     */
    private String createUploadUrl(String assetUploadUrl, String assetName) throws Exception {
        int idx = assetUploadUrl.indexOf("{?name");
        if (idx < 0) {
            throw new Exception("Invalid Asset Upload URL Pattern: " + assetUploadUrl);
        }
        return String.format("%s?name=%s", assetUploadUrl.substring(0, idx), assetName);
    }

    /**
     * @param releaseArtifactFile
     */
    private byte[] loadArtifactData(File releaseArtifactFile) throws Exception {
        System.out.println("Loading artifact content: " + releaseArtifactFile.getName());
        byte [] buffer = new byte[(int) releaseArtifactFile.length()];
        try (InputStream is = new FileInputStream(releaseArtifactFile)) {
            IOUtils.readFully(is, buffer);
            return buffer;
        } catch (IOException e) {
            throw new Exception(e);
        }
    }
    
    private String repoToName() {
        if (repository.equals("apicurio-studio")) {
            return "Apicurio Studio";
        } else if (repository.equals("apicurito")) {
            return "Apicurito";
        } else {
            return repository;
        }
    }
    
//    public static void testMain(String[] args) throws Exception {
//        String pat = "XXXYYYZZZ";
//        String since = "2019-03-01T12:00:00Z";
//        List<JSONObject> list = getIssuesForRelease(since, pat);
//        System.out.println("Found " + list.size() + " issues!");
//    }

}
