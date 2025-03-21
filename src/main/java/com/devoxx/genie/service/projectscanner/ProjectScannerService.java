package com.devoxx.genie.service.projectscanner;

import com.devoxx.genie.model.ScanContentResult;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Setter
@Getter
public class ProjectScannerService {
    private static final Logger LOG = Logger.getInstance(ProjectScannerService.class.getName());

    protected FileScanner fileScanner;

    protected ContentExtractor contentExtractor;

    protected TokenCalculator tokenCalculator;

    // Use a Map to store ProjectFileIndex instances per project
private final Map<String, ProjectFileIndex> projectFileIndexMap = new HashMap<String, ProjectFileIndex>();

    public ProjectScannerService() {
        this.fileScanner = new FileScanner();
        this.contentExtractor = new ContentExtractor();
        this.tokenCalculator = new TokenCalculator();
    }

    public static ProjectScannerService getInstance() {
        return ApplicationManager.getApplication().getService(ProjectScannerService.class);
    }

    /**
     * Get the ProjectFileIndex for a specific project
     * This ensures we always use the correct context for each project
     * 
     * @param project The project to get the file index for
     * @return The ProjectFileIndex for the specified project
     */
    private ProjectFileIndex getProjectFileIndex(@NotNull Project project) {
        String projectLocationHash = project.getLocationHash();
        LOG.debug("Getting ProjectFileIndex for project: " + project.getName() + 
                  " with location hash: " + projectLocationHash);
        
        return projectFileIndexMap.computeIfAbsent(projectLocationHash, hash -> {
            LOG.info("Creating new ProjectFileIndex for project: " + project.getName());
            return ProjectFileIndex.getInstance(project);
        });
    }

    public ScanContentResult scanProject(Project project,
                                         VirtualFile startDirectory,
                                         int windowContextMaxTokens,
                                         boolean isTokenCalculation) {

        // Always get the correct ProjectFileIndex for this project
        ProjectFileIndex projectFileIndex = getProjectFileIndex(project);
        LOG.debug("Scanning project: " + project.getName() + " with directory: " + 
                 (startDirectory != null ? startDirectory.getPath() : "null"));

        ScanContentResult scanContentResult = new ScanContentResult();
        ReadAction.run(() -> {
            fileScanner.reset();
            fileScanner.initGitignoreParser(project, startDirectory);

            String content = scanContent(project, startDirectory, windowContextMaxTokens, isTokenCalculation, scanContentResult, projectFileIndex);
            fileScanner.getIncludedFiles().forEach(scanContentResult::addFile);

            scanContentResult.setTokenCount(tokenCalculator.calculateTokens(content));
            scanContentResult.setContent(content);
            scanContentResult.setFileCount(fileScanner.getFileCount());
            scanContentResult.setSkippedFileCount(fileScanner.getSkippedFileCount());
            scanContentResult.setSkippedDirectoryCount(fileScanner.getSkippedDirectoryCount());
        });
        return scanContentResult;
    }

    // For backward compatibility, keep the old method signature and delegate to the new one
    private @NotNull String scanContent(Project project,
                                       VirtualFile startDirectory,
                                       int windowContextMaxTokens,
                                       boolean isTokenCalculation,
                                       ScanContentResult scanContentResult) {
        return scanContent(project, startDirectory, windowContextMaxTokens, isTokenCalculation, 
                         scanContentResult, getProjectFileIndex(project));
    }

    // Changed from private to public for better testability
    public @NotNull String scanContent(Project project,
                                       VirtualFile startDirectory,
                                       int windowContextMaxTokens,
                                       boolean isTokenCalculation,
                                       ScanContentResult scanContentResult,
                                       ProjectFileIndex projectFileIndex) {
        // We're now using the projectFileIndex parameter directly

        StringBuilder directoryStructure = new StringBuilder();
        String fileContents;

        if (startDirectory == null) {
            // Case 1: No directory provided, scan all modules
            VirtualFile rootDirectory = fileScanner.scanProjectModules(project);
            directoryStructure.append(fileScanner.generateSourceTreeRecursive(rootDirectory, 0));
            // Use the provided projectFileIndex
            List<VirtualFile> files = fileScanner.scanDirectory(projectFileIndex, rootDirectory, scanContentResult);
            fileContents = extractAllFileContents(files);
        } else if (startDirectory.isDirectory()) {
            // Case 2: Directory provided
            directoryStructure.append(fileScanner.generateSourceTreeRecursive(startDirectory, 0));
            // Use the provided projectFileIndex
            List<VirtualFile> files = fileScanner.scanDirectory(projectFileIndex, startDirectory, scanContentResult);
            fileContents = extractAllFileContents(files);
        } else {
            // Case 3: Single file provided
            return handleSingleFile(startDirectory);
        }

        String fullContent = contentExtractor.combineContent(directoryStructure.toString(), fileContents);

        // Truncate if necessary
        return tokenCalculator.truncateToTokens(fullContent, windowContextMaxTokens, isTokenCalculation);
    }

    // Changed from private to public for better testability
    public @NotNull String handleSingleFile(@NotNull VirtualFile file) {
        StringBuilder result = new StringBuilder("File:\n");
        result.append(file.getName()).append("\n\nFile Contents:\n");

        if (fileScanner.shouldIncludeFile(file)) {
            result.append(contentExtractor.extractFileContent(file));
        } else {
            LOG.debug("Skipping file: " + file.getPath() + " (excluded by settings or .gitignore)");
        }

        return result.toString();
    }

    // Changed from private to public for better testability
    public @NotNull String extractAllFileContents(@NotNull List<VirtualFile> files) {
        return files.stream()
                .map(contentExtractor::extractFileContent)
                .collect(Collectors.joining());
    }
}
