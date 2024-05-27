package com.devoxx.genie.ui;

import com.devoxx.genie.chatmodel.ChatModelFactory;
import com.devoxx.genie.chatmodel.ChatModelFactoryProvider;
import com.devoxx.genie.chatmodel.ChatModelProvider;
import com.devoxx.genie.model.enumarations.ModelProvider;
import com.devoxx.genie.model.request.ChatMessageContext;
import com.devoxx.genie.model.request.EditorInfo;
import com.devoxx.genie.service.ChatMemoryService;
import com.devoxx.genie.service.ChatPromptExecutor;
import com.devoxx.genie.service.FileListManager;
import com.devoxx.genie.service.SettingsStateService;
import com.devoxx.genie.ui.component.ContextPopupMenu;
import com.devoxx.genie.ui.component.JHoverButton;
import com.devoxx.genie.ui.component.PromptInputArea;
import com.devoxx.genie.ui.listener.SettingsChangeListener;
import com.devoxx.genie.ui.panel.ConversationPanel;
import com.devoxx.genie.ui.panel.FileSelectionPanelFactory;
import com.devoxx.genie.ui.panel.PromptContextFileListPanel;
import com.devoxx.genie.ui.panel.PromptOutputPanel;
import com.devoxx.genie.ui.util.EditorUtil;
import com.devoxx.genie.ui.util.NotificationUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import dev.langchain4j.data.message.UserMessage;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.ResourceBundle;

import static com.devoxx.genie.action.AddSnippetAction.SELECTED_TEXT_KEY;
import static com.devoxx.genie.chatmodel.LLMProviderConstant.getLLMProviders;
import static com.devoxx.genie.ui.util.DevoxxGenieIcons.*;
import static javax.swing.SwingUtilities.invokeLater;

/**
 * The Devoxx Genie Tool Window Content.
 */
public class DevoxxGenieToolWindowContent implements SettingsChangeListener, ConversationStarter {

    private final Logger LOG = Logger.getInstance(DevoxxGenieToolWindowContent.class);

    private final Project project;
    private final ResourceBundle resourceBundle = ResourceBundle.getBundle("messages");
    public static final String COMBO_BOX_CHANGED = "comboBoxChanged";

    private final ChatModelProvider chatModelProvider = new ChatModelProvider();

    @Getter
    private final JPanel contentPanel = new JPanel();
    private final ComboBox<String> llmProvidersComboBox = new ComboBox<>();
    private final ComboBox<String> modelNameComboBox = new ComboBox<>();

    private PromptInputArea promptInputComponent;
    private PromptOutputPanel promptOutputPanel;
    private PromptContextFileListPanel promptContextFileListPanel;

    private final JButton submitBtn = new JHoverButton(SubmitIcon, true);
    private final JButton addFileBtn = new JHoverButton(AddFileIcon, true);

    private final ChatPromptExecutor chatPromptExecutor;
    private final SettingsStateService settingsState;
    private ConversationPanel conversationPanel;
    private boolean isInitializationComplete = false;
    private final EditorFileButtonManager editorFileButtonManager;

    /**
     * The Devoxx Genie Tool Window Content constructor.
     *
     * @param toolWindow the tool window
     */
    public DevoxxGenieToolWindowContent(@NotNull ToolWindow toolWindow) {

        project = toolWindow.getProject();
        settingsState = SettingsStateService.getInstance();
        chatPromptExecutor = new ChatPromptExecutor();

        editorFileButtonManager = new EditorFileButtonManager(project, addFileBtn);

        setupUI();
        setLastSelectedProvider();

        isInitializationComplete = true;
    }

    /**
     * Set the last selected LLM provider or show default.
     */
    private void setLastSelectedProvider() {
        String lastSelectedProvider = settingsState.getLastSelectedProvider();
        if (lastSelectedProvider != null) {
            llmProvidersComboBox.setSelectedItem(lastSelectedProvider);
            updateModelNamesComboBox(ModelProvider.valueOf(lastSelectedProvider));
        } else {
            Object selectedItem = llmProvidersComboBox.getSelectedItem();
            if (selectedItem != null) {
                updateModelNamesComboBox(ModelProvider.valueOf((String) selectedItem));
            }
        }
    }

    /**
     * Set up the UI Components: top panel and splitter.
     */
    private void setupUI() {
        contentPanel.setLayout(new BorderLayout());
        contentPanel.add(createTopPanel(), BorderLayout.NORTH);
        contentPanel.add(createSplitter(), BorderLayout.CENTER);
    }

    /**
     * Create the top panel.
     *
     * @return the top panel
     */
    private @NotNull JPanel createTopPanel() {
        promptInputComponent = new PromptInputArea(resourceBundle);
        promptOutputPanel = new PromptOutputPanel(resourceBundle);
        promptContextFileListPanel = new PromptContextFileListPanel(project);
        conversationPanel = new ConversationPanel(project, this);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(createSelectionPanel(), BorderLayout.NORTH);
        topPanel.add(conversationPanel, BorderLayout.CENTER);
        return topPanel;
    }

    /**
     * Create the splitter.
     *
     * @return the splitter
     */
    private @NotNull Splitter createSplitter() {
        Splitter splitter = new Splitter(true, 0.8f);
        splitter.setFirstComponent(promptOutputPanel);
        splitter.setSecondComponent(createInputPanel());
        splitter.setHonorComponentsMinimumSize(true);
        return splitter;
    }

    /**
     * Refresh the LLM providers dropdown because the Settings have been changed.
     */
    public void settingsChanged() {
        llmProvidersComboBox.removeAllItems();
        addLLMProvidersToComboBox();
    }

    /**
     * Add the LLM providers to combobox.
     * Only show the cloud-based LLM providers for which we have an API Key.
     */
    private void addLLMProvidersToComboBox() {
        getLLMProviders().stream()
            .sorted()
            .forEach(llmProvidersComboBox::addItem);
    }

    /**
     * Create the LLM and model name selection panel.
     *
     * @return the selection panel
     */
    @NotNull
    private JPanel createSelectionPanel() {
        JPanel toolPanel = createToolPanel();
        addLLMProvidersToComboBox();

        modelNameComboBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, modelNameComboBox.getPreferredSize().height));
        modelNameComboBox.addActionListener(this::processModelNameSelection);

        return toolPanel;
    }

    /**
     * Create the tool panel.
     *
     * @return the tool panel
     */
    private @NotNull JPanel createToolPanel() {
        JPanel toolPanel = new JPanel();
        toolPanel.setLayout(new BoxLayout(toolPanel, BoxLayout.Y_AXIS));
        toolPanel.add(createProviderPanel());
        toolPanel.add(Box.createVerticalStrut(5));
        toolPanel.add(modelNameComboBox);
        return toolPanel;
    }

    /**
     * Create the LLM provider panel.
     *
     * @return the provider panel
     */
    private @NotNull JPanel createProviderPanel() {
        JPanel providerPanel = new JPanel(new BorderLayout(), true);
        providerPanel.add(llmProvidersComboBox, BorderLayout.CENTER);
        llmProvidersComboBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, llmProvidersComboBox.getPreferredSize().height));
        llmProvidersComboBox.addActionListener(this::handleModelProviderSelectionChange);
        return providerPanel;
    }

    /**
     * Create the chat input panel.
     *
     * @return the input panel
     */
    @NotNull
    private JPanel createInputPanel() {
        JPanel buttonPanel = createButtonPanel();
        return createSubmitPanel(buttonPanel);
    }

    /**
     * Create the Submit and add File button Panel.
     *
     * @return the button panel
     */
    private @NotNull JPanel createButtonPanel() {
        addFileBtn.setToolTipText("Add file(s) to prompt context");
        addFileBtn.addActionListener(this::selectFilesForPromptContext);

        submitBtn.setToolTipText("Submit the prompt");
        submitBtn.addActionListener(this::onSubmitPrompt);

        JPanel buttonPanel = new JPanel(new BorderLayout());
        buttonPanel.add(submitBtn, BorderLayout.WEST);
        buttonPanel.add(addFileBtn, BorderLayout.EAST);
        return buttonPanel;
    }

    /**
     * Create the Submit panel.
     *
     * @param buttonPanel the button panel
     * @return the Submit panel
     */
    private @NotNull JPanel createSubmitPanel(JPanel buttonPanel) {
        JPanel submitPanel = new JPanel(new BorderLayout());
        submitPanel.setMinimumSize(new Dimension(Integer.MAX_VALUE, 100));
        submitPanel.add(promptContextFileListPanel, BorderLayout.NORTH);
        submitPanel.add(new JBScrollPane(promptInputComponent), BorderLayout.CENTER);
        submitPanel.add(new JBScrollPane(buttonPanel), BorderLayout.SOUTH);
        return submitPanel;
    }

    /**
     * Start a new conversation.
     */
    @Override
    public void startNewConversation() {
        conversationPanel.updateNewConversationLabel();
        promptInputComponent.clear();
        promptOutputPanel.clear();
        FileListManager.getInstance().clear();
        ChatMemoryService.getInstance().clear();
        enableButtons();
    }

    /**
     * Add files to the prompt context.
     */
    private void selectFilesForPromptContext(ActionEvent e) {
        JBPopup popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(FileSelectionPanelFactory.createPanel(project), null)
            .setTitle("Double-Click To Add To Prompt Context")
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(false)
            .createPopup();

        if (addFileBtn.isShowing()) {
            new ContextPopupMenu().show(submitBtn,
                popup,
                this.getContentPanel().getSize().width,
                this.promptInputComponent.getLocationOnScreen().y);
        }
    }

    /**
     * Submit the user prompt.
     */
    private void onSubmitPrompt(ActionEvent e) {
        String userPromptText = promptInputComponent.getText();
        if (userPromptText.isEmpty()) {
            return;
        }

        invokeLater(() -> {
            if (SettingsStateService.getInstance().getStreamMode()) {
                submitBtn.setEnabled(false);
            }
            submitBtn.setIcon(StopIcon);
            submitBtn.setToolTipText("Prompt is running, please be patient...");
        });

        ChatMessageContext chatMessageContext =
            createChatMessageContext(userPromptText, editorFileButtonManager.getSelectedTextEditor());

        disableButtons();

        chatPromptExecutor.updatePromptWithCommandIfPresent(chatMessageContext, promptOutputPanel);

        chatPromptExecutor.executePrompt(chatMessageContext, promptOutputPanel, this::enableButtons);
    }

    /**
     * Get the chat message context.
     *
     * @param userPrompt the user prompt
     * @param editor     the editor
     * @return the prompt context with language and text
     */
    private @NotNull ChatMessageContext createChatMessageContext(String userPrompt,
                                                                 Editor editor) {
        ChatMessageContext chatMessageContext = new ChatMessageContext();
        chatMessageContext.setProject(project);
        chatMessageContext.setName(String.valueOf(System.currentTimeMillis()));
        chatMessageContext.setUserPrompt(userPrompt);
        chatMessageContext.setUserMessage(UserMessage.userMessage(userPrompt));
        chatMessageContext.setLlmProvider((String) llmProvidersComboBox.getSelectedItem());
        chatMessageContext.setModelName((String) modelNameComboBox.getSelectedItem());

        if (settingsState.getStreamMode()) {
            chatMessageContext.setStreamingChatLanguageModel(chatModelProvider.getStreamingChatLanguageModel(chatMessageContext));
        } else {
            chatMessageContext.setChatLanguageModel(chatModelProvider.getChatLanguageModel(chatMessageContext));
        }

        setChatTimeout(chatMessageContext);

        addSelectedCode(userPrompt, editor, chatMessageContext);

        return chatMessageContext;
    }

    /**
     * Add the selected code to the chat message context.
     *
     * @param userPrompt         the user prompt
     * @param editor             the editor
     * @param chatMessageContext the chat message context
     */
    private void addSelectedCode(String userPrompt,
                                 Editor editor,
                                 ChatMessageContext chatMessageContext) {
        List<VirtualFile> files = FileListManager.getInstance().getFiles();
        if (!files.isEmpty()) {
            addSelectedFiles(chatMessageContext, userPrompt, files);
        } else if (editor != null) {
            EditorInfo editorInfo = createEditorInfo(editor);
            chatMessageContext.setEditorInfo(editorInfo);
        }
    }

    /**
     * Create the editor info.
     *
     * @param editor the editor
     * @return the editor info
     */
    private @NotNull EditorInfo createEditorInfo(Editor editor) {
        EditorInfo editorInfo = EditorUtil.getEditorInfo(project, editor);
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText != null) {
            editorInfo.setSelectedText(selectedText);
        } else {
            editorInfo.setSelectedText(editor.getDocument().getText());
            editorInfo.setSelectedFiles(List.of(editor.getVirtualFile()));
        }
        return editorInfo;
    }

    /**
     * Set the timeout for the chat message context.
     *
     * @param chatMessageContext the chat message context
     */
    private void setChatTimeout(ChatMessageContext chatMessageContext) {
        Integer timeout = settingsState.getTimeout();
        if (timeout == 0) {
            chatMessageContext.setTimeout(60);
        } else {
            chatMessageContext.setTimeout(timeout);
        }
    }

    /**
     * Get the prompt context from the selected files.
     *
     * @param chatMessageContext the chat message context
     * @param userPrompt         the user prompt
     * @param files              the files
     */
    private void addSelectedFiles(@NotNull ChatMessageContext chatMessageContext,
                                  String userPrompt,
                                  List<VirtualFile> files) {
        chatMessageContext.setEditorInfo(new EditorInfo(files));
        chatMessageContext.setContext(getUserPromptWithContext(userPrompt, files));
    }

    /**
     * Get user prompt with context.
     * TODO move this to a dedicated class
     *
     * @param userPrompt the user prompt
     * @param files      the files
     * @return the user prompt with context
     */
    private @NotNull String getUserPromptWithContext(String userPrompt,
                                                     @NotNull List<VirtualFile> files) {
        StringBuilder userPromptContext = new StringBuilder();
        FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
        files.forEach(file -> ApplicationManager.getApplication().runReadAction(() -> {
            if (file.getFileType().getName().equals("UNKNOWN")) {
                userPromptContext.append("Filename: ").append(file.getName()).append("\n");
                userPromptContext.append("Code Snippet: ").append(file.getUserData(SELECTED_TEXT_KEY)).append("\n");
            } else {
                Document document = fileDocumentManager.getDocument(file);
                if (document != null) {
                    userPromptContext.append("Filename: ").append(file.getName()).append("\n");
                    String content = document.getText();
                    userPromptContext.append(content).append("\n");
                } else {
                    NotificationUtil.sendNotification(project, "Error reading file: " + file.getName());
                }
            }
        }));

        userPromptContext.append(userPrompt);
        return userPromptContext.toString();
    }

    /**
     * Disable the prompt input component.
     */
    private void disableButtons() {
        promptInputComponent.setEnabled(false);
    }

    /**
     * Enable the prompt input component and reset submit icon.
     */
    private void enableButtons() {
        submitBtn.setIcon(SubmitIcon);
        submitBtn.setEnabled(true);
        promptInputComponent.setEnabled(true);
    }

    /**
     * Process the model name selection.
     */
    private void processModelNameSelection(@NotNull ActionEvent e) {
        if (e.getActionCommand().equals(COMBO_BOX_CHANGED)) {
            JComboBox<?> comboBox = (JComboBox<?>) e.getSource();
            String selectedModel = (String) comboBox.getSelectedItem();
            if (selectedModel != null) {
                settingsState.setLastSelectedModel(selectedModel);
            }
        }
    }

    /**
     * Process the model provider selection change.
     * Set the model provider and update the model names.
     */
    private void handleModelProviderSelectionChange(@NotNull ActionEvent e) {
        if (!e.getActionCommand().equals(COMBO_BOX_CHANGED) || !isInitializationComplete) return;

        JComboBox<?> comboBox = (JComboBox<?>) e.getSource();

        String selectedLLMProvider = (String) comboBox.getSelectedItem();
        if (selectedLLMProvider == null) return;

        settingsState.setLastSelectedProvider(selectedLLMProvider);
        ModelProvider provider = ModelProvider.valueOf(selectedLLMProvider);

        updateModelNamesComboBox(provider);
    }

    /**
     * Update the model names combobox.
     *
     * @param provider the model provider
     */
    private void updateModelNamesComboBox(ModelProvider provider) {
        if (provider == null) {
            return;
        }

        modelNameComboBox.setVisible(true);
        modelNameComboBox.removeAllItems();

        ChatModelFactoryProvider
            .getFactoryByProvider(provider)
            .ifPresentOrElse(this::populateModelNames, this::hideModelNameComboBox);

        String lastSelectedModel = settingsState.getLastSelectedModel();
        if (lastSelectedModel != null) {
            modelNameComboBox.setSelectedItem(lastSelectedModel);
        }
    }

    /**
     * Populate the model names.
     *
     * @param chatModelFactory the chat model factory
     */
    private void populateModelNames(@NotNull ChatModelFactory chatModelFactory) {
        List<String> modelNames = chatModelFactory.getModelNames();
        if (modelNames.isEmpty()) {
            hideModelNameComboBox();
        } else {
            modelNames.stream()
                .sorted()
                .forEach(modelNameComboBox::addItem);
        }
    }

    /**
     * Hide the model name combobox.
     */
    private void hideModelNameComboBox() {
        modelNameComboBox.setVisible(false);
    }
}
