package de.endrullis.idea.postfixtemplates.templates;

import com.intellij.AppTopics;
import com.intellij.codeInsight.completion.CompletionInitializationContext;
import com.intellij.codeInsight.completion.JavaCompletionContributor;
import com.intellij.codeInsight.template.postfix.templates.PostfixLiveTemplate;
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate;
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerAdapter;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileContentsChangedAdapter;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.OrderedSet;
import com.intellij.util.messages.MessageBusConnection;
import de.endrullis.idea.postfixtemplates.language.CptFileType;
import de.endrullis.idea.postfixtemplates.language.CptUtil;
import de.endrullis.idea.postfixtemplates.language.psi.CptFile;
import de.endrullis.idea.postfixtemplates.language.psi.CptMapping;
import de.endrullis.idea.postfixtemplates.language.psi.CptMappings;
import de.endrullis.idea.postfixtemplates.language.psi.CptTemplate;
import de.endrullis.idea.postfixtemplates.settings.CptApplicationSettings;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

public class CustomPostfixTemplateProvider implements PostfixTemplateProvider, CptApplicationSettings.SettingsChangedListener {
	private Set<PostfixTemplate> templates;

	/**
	 * Template file change listener.
	 */
	private FileDocumentManagerListener templateFileChangeListener = new FileDocumentManagerAdapter() {
		@Override
		public void beforeDocumentSaving(@NotNull Document d) {
			VirtualFile vFile = FileDocumentManager.getInstance().getFile(d);
			if (vFile != null && vFile.getCanonicalPath().replace('\\', '/').startsWith(CptUtil.getTemplatesPath().getAbsolutePath().replace('\\', '/'))) {
				reloadTemplates();
			}
		}
	};

	@NotNull
	private static Editor createEditor() {
		EditorFactory editorFactory = EditorFactory.getInstance();
		Document editorDocument = editorFactory.createDocument("");
		return editorFactory.createEditor(editorDocument, null, CptFileType.INSTANCE, true);
	}

	public CustomPostfixTemplateProvider() {
		LocalFileSystem.getInstance().addRootToWatch(CptUtil.getTemplatesPath().getAbsolutePath(), true);
		LocalFileSystem.getInstance().addVirtualFileListener(new VirtualFileContentsChangedAdapter() {
			@Override
			protected void onFileChange(@NotNull VirtualFile virtualFile) {
				reloadTemplates();
			}

			@Override
			protected void onBeforeFileChange(@NotNull VirtualFile virtualFile) {
			}
		});

		MessageBusConnection messageBus = ApplicationManager.getApplication().getMessageBus().connect();

		// listen to settings changes
		messageBus.subscribe(CptApplicationSettings.SettingsChangedListener.TOPIC, this);

		// listen to file changes of template file
		messageBus.subscribe(AppTopics.FILE_DOCUMENT_SYNC, templateFileChangeListener);

		reload(CptApplicationSettings.getInstance());
	}

	private void reload(CptApplicationSettings settings) {
		reloadTemplates();
	}

	@Override
	public void reloadTemplates() {
		CptUtil.getTemplateFile("java").ifPresent(file -> {
			if (file.exists()) {
				templates = loadTemplatesFrom(file);
			}
		});
	}

	public Set<PostfixTemplate> loadTemplatesFrom(@NotNull File file) {
		VirtualFile vFile = LocalFileSystem.getInstance().findFileByIoFile(file);
		if (vFile != null) {
			return loadTemplatesFrom(vFile);
		} else {
			return new OrderedSet<>();
		}
	}

	public Set<PostfixTemplate> loadTemplatesFrom(@NotNull VirtualFile vFile) {
		Set<PostfixTemplate> templates = new OrderedSet<>();

		Project project = ProjectManager.getInstance().getOpenProjects()[0];

		CptFile cptFile = (CptFile) PsiManager.getInstance(project).findFile(vFile);
		if (cptFile != null) {
			CptTemplate[] cptTemplates = PsiTreeUtil.getChildrenOfType(cptFile, CptTemplate.class);
			if (cptTemplates != null) {
				for (CptTemplate cptTemplate : cptTemplates) {
					CptMappings[] cptMappings = PsiTreeUtil.getChildrenOfType(cptTemplate, CptMappings.class);
					if (cptMappings != null && cptMappings.length > 0) {
						CptMapping[] mappings = PsiTreeUtil.getChildrenOfType(cptMappings[0], CptMapping.class);
						if (mappings != null) {
							for (CptMapping mapping : mappings) {
								templates.add(new CustomStringPostfixTemplate(mapping.getClassName(), cptTemplate.getTemplateName(),
									cptTemplate.getTemplateDescription(), mapping.getReplacementString()));
							}
						}
					}
				}
			}
		}

		return combineTemplatesWithSameName(templates);
	}

	private Set<PostfixTemplate> combineTemplatesWithSameName(Set<PostfixTemplate> templates) {
		// group templates by name
		Map<String, List<PostfixTemplate>> key2templates = templates.stream().collect(
			Collectors.groupingBy(
				PostfixTemplate::getKey, toList()
			)
		);

		// combine templates with the same name
		Set<PostfixTemplate> combinedTemplates = new OrderedSet<>();
		for (List<PostfixTemplate> theseTemplates : key2templates.values()) {
			if (theseTemplates.size() == 1) {
				combinedTemplates.add(theseTemplates.get(0));
			} else {
				String example = templates.stream().distinct().count() > 1 ? theseTemplates.get(0).getExample() : "";
				combinedTemplates.add(new CombinedPostfixTemplate(theseTemplates.get(0).getKey(), example, theseTemplates));
			}
		}

		return combinedTemplates;
	}

	@NotNull
	@Override
	public Set<PostfixTemplate> getTemplates() {
		return templates;
	}

	@Override
	public boolean isTerminalSymbol(char currentChar) {
		return currentChar == '.' || currentChar == '!';
	}

	@Override
	public void preExpand(@NotNull final PsiFile file, @NotNull final Editor editor) {
		ApplicationManager.getApplication().assertIsDispatchThread();

		if (isSemicolonNeeded(file, editor)) {
			ApplicationManager.getApplication().runWriteAction(() -> CommandProcessor.getInstance().runUndoTransparentAction(
				() -> {
					EditorModificationUtil.insertStringAtCaret(editor, ";", false, false);
					PsiDocumentManager.getInstance(file.getProject()).commitDocument(editor.getDocument());
				}));
		}
	}

	@Override
	public void afterExpand(@NotNull final PsiFile file, @NotNull final Editor editor) {
	}

	@NotNull
	@Override
	public PsiFile preCheck(@NotNull PsiFile copyFile, @NotNull Editor realEditor, int currentOffset) {
		Document document = copyFile.getViewProvider().getDocument();
		assert document != null;
		CharSequence sequence = document.getCharsSequence();
		StringBuilder fileContentWithSemicolon = new StringBuilder(sequence);
		if (isSemicolonNeeded(copyFile, realEditor)) {
			fileContentWithSemicolon.insert(currentOffset, ';');
			return PostfixLiveTemplate.copyFile(copyFile, fileContentWithSemicolon);
		}

		return copyFile;
	}

	private static boolean isSemicolonNeeded(@NotNull PsiFile file, @NotNull Editor editor) {
		return JavaCompletionContributor.semicolonNeeded(editor, file, CompletionInitializationContext.calcStartOffset(editor.getCaretModel().getCurrentCaret()));
	}

	@Override
	public void onSettingsChange(@NotNull CptApplicationSettings settings) {
		reload(settings);
	}

}
