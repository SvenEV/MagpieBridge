package magpiebridge.core;

import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.classLoader.SourceFileModule;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.io.TemporaryFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * @author Julian Dolby and Linghui Luo
 */
public class MagpieServer implements LanguageServer, LanguageClientAware {
  protected LanguageClient client;

  protected Map<String, Collection<ServerAnalysis>> languageAnalyses;
  protected Map<String, Map<Module, URI>> languageSourceFiles;
  protected Map<String, IProjectService> languageProjectServices;

  protected Map<URL, List<Diagnostic>> diagnostics;
  protected Map<URL, NavigableMap<Position, Hover>> hovers;
  protected Map<URL, List<CodeLens>> codeLenses;

  protected Optional<Path> rootPath;
  private Map<String, String> serverClientUri;
  private Socket connectionSocket;
  public Logger logger;

  public MagpieServer() {
    languageAnalyses = new HashMap<String, Collection<ServerAnalysis>>();
    languageSourceFiles = new HashMap<String, Map<Module, URI>>();
    languageProjectServices = new HashMap<String, IProjectService>();
    diagnostics = new HashMap<>();
    hovers = new HashMap<>();
    codeLenses = new HashMap<>();
    serverClientUri = new HashMap<>();
    logger = new Logger();
  }

  public void launchOnStdio() {
    launchOnStream(Utils.logStream(System.in, "magpie.in"), Utils.logStream(System.out, "magpie.out"));
  }

  public void launchOnStream(InputStream in, OutputStream out) {
    Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(this, Utils.logStream(in, "magpie.in"),
        Utils.logStream(out, "magpie.out"), true, new PrintWriter(System.err));
    connect(launcher.getRemoteProxy());
    launcher.startListening();
  }

  public void launchOnSocketPort(String host, int port) {
    try {
      connectionSocket = new Socket(host, port);
      Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(this,
          Utils.logStream(connectionSocket.getInputStream(), "magpie.in"),
          Utils.logStream(connectionSocket.getOutputStream(), "magpie.out"));
      connect(launcher.getRemoteProxy());
      launcher.startListening();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void launchOnWebSocketPort() {

  }

  @Override
  public void connect(LanguageClient client) {
    this.client = client;
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    logger.logClientMsg(params.toString());
    System.err.println("client:\n" + params);
    if (params.getRootUri() != null) {
      this.rootPath = Optional.ofNullable(Paths.get(URI.create(params.getRootUri())));
    } else {
      this.rootPath = Optional.empty();
    }
    final ServerCapabilities caps = new ServerCapabilities();
    caps.setHoverProvider(true);

    caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
    // TODO: Tried this to receive open/close events in VS Code, but didn't work:'
    // (see https://stackoverflow.com/questions/55050629/language-server-how-to-enable-ondidopentextdocument-events)
    //TextDocumentSyncOptions syncOptions = new TextDocumentSyncOptions();
    //syncOptions.setOpenClose(true);
    //caps.setTextDocumentSync(syncOptions);

    caps.setDocumentHighlightProvider(true);

    CodeLensOptions cl = new CodeLensOptions();
    cl.setResolveProvider(true);
    caps.setCodeLensProvider(cl);
    caps.setDocumentSymbolProvider(true);
    caps.setDefinitionProvider(true);
    caps.setReferencesProvider(true);
    ExecuteCommandOptions exec = new ExecuteCommandOptions();
    exec.setCommands(new LinkedList<String>());
    caps.setExecuteCommandProvider(exec);
    caps.setCodeActionProvider(false);
    InitializeResult v = new InitializeResult(caps);
    System.err.println("server:\n" + caps);
    logger.logServerMsg(v.toString());
    return CompletableFuture.completedFuture(v);
  }

  @Override
  public void initialized(InitializedParams params) {
    logger.logClientMsg(params.toString());
    System.err.println("client:\n" + params);
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    return CompletableFuture.completedFuture(new Object());
  }

  @Override
  public void exit() {
    try {
      connectionSocket.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public boolean addSource(String language, String text, String clientUri) {
    try {
      File file = File.createTempFile("temp", ".java");
      file.deleteOnExit();
      TemporaryFile.stringToFile(file, text);
      Module sourceFile = new SourceFileModule(file, clientUri.toString(), null);
      String serverUri = Paths.get(file.toURI()).toUri().toString();
      serverClientUri.put(serverUri, clientUri);
      if (!languageSourceFiles.containsKey(language)) {
        languageSourceFiles.put(language, new HashMap<Module, URI>());
      }
      if (!languageSourceFiles.get(language).containsKey(sourceFile)) {
        languageSourceFiles.get(language).put(sourceFile, new URI(clientUri));
        return true;
      }
    } catch (IOException e) {
      e.printStackTrace();
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }
    return false;
  }

  /**
   * Add project service for different languages.
   *
   * @param language
   */
  public void addProjectService(String language, IProjectService projectService) {
    if (!this.languageProjectServices.containsKey(language)) {
      this.languageProjectServices.put(language, projectService);
    }
  }

  public void addAnalysis(String language, ServerAnalysis analysis) {
    if (!languageAnalyses.containsKey(language)) {
      languageAnalyses.put(language, new HashSet<ServerAnalysis>());
    }
    languageAnalyses.get(language).add(analysis);
  }

  public void doAnalysis(String language) {
    Map<Module, URI> sourceFiles = this.languageSourceFiles.get(language);
    for (ServerAnalysis analysis : languageAnalyses.get(language)) {
      analysis.analyze(sourceFiles.keySet(), this);
    }
  }

  public void consume(Collection<AnalysisResult> results, String source) {
    for (AnalysisResult result : results) {
      URL url = result.position().getURL();
      List<Diagnostic> diagList = null;
      if (this.diagnostics.containsKey(url)) {
        diagList = diagnostics.get(url);
      } else {
        diagList = new ArrayList<>();
        this.diagnostics.put(url, diagList);
      }
      switch (result.kind()) {
        case Diagnostic:
          createDiagnosticConsumer(diagList, source).accept(result);
          break;
        case Hover:
          createHoverConsumer().accept(result);
          break;
        case CodeLens:
          createCodeLensConsumer().accept(result);
          break;
        default:
          break;
      }
    }
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return new MagpieTextDocumentService(this);
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return new MagpieWorkspaceService(this);
  }

  public IProjectService getProjectService(String language) {
    return languageProjectServices.get(language);
  }

  protected Consumer<AnalysisResult> createDiagnosticConsumer(List<Diagnostic> diagList, String source) {
    Consumer<AnalysisResult> consumer = result -> {
      Diagnostic d = new Diagnostic();
      d.setMessage(result.toString(false));
      d.setRange(Utils.getLocationFrom(result.position()).getRange());
      d.setSource(source);
      List<DiagnosticRelatedInformation> relatedList = new ArrayList<>();
      for (Pair<Position, String> related : result.related()) {
        DiagnosticRelatedInformation di = new DiagnosticRelatedInformation();
        di.setLocation(Utils.getLocationFrom(related.fst));
        di.setMessage(related.snd);
        relatedList.add(di);
      }
      d.setRelatedInformation(relatedList);
      d.setSeverity(result.severity());
      if (!diagList.contains(d)) {
        diagList.add(d);
      }
      PublishDiagnosticsParams pdp = new PublishDiagnosticsParams();
      pdp.setDiagnostics(diagList);
      String serverUri = result.position().getURL().toString();
      if (System.getProperty("os.name").toLowerCase().indexOf("win") >= 0) {
        // take care of uri in windows
        if (!serverUri.startsWith("file:///")) {
          serverUri = serverUri.replace("file://", "file:///");
        }
      }
      String clientUri = serverClientUri.get(serverUri);
      pdp.setUri(clientUri);
      client.publishDiagnostics(pdp);
      logger.logServerMsg(pdp.toString());
      System.err.println("server:\n" + pdp);
    };
    return consumer;
  }

  protected Consumer<AnalysisResult> createHoverConsumer() {
    Consumer<AnalysisResult> consumer = result -> {
      Hover hover = new Hover();
      List<Either<String, MarkedString>> contents = new ArrayList<>();
      Either<String, MarkedString> content = Either.forLeft(result.toString(true));
      contents.add(content);
      hover.setContents(contents);
      hover.setRange(Utils.getLocationFrom(result.position()).getRange());

    };
    return consumer;
  }

  protected Consumer<AnalysisResult> createCodeLensConsumer() {
    Consumer<AnalysisResult> consumer = result -> {
      CodeLens codeLens = new CodeLens();

      codeLens.setRange(Utils.getLocationFrom(result.position()).getRange());

    };
    return consumer;
  }

  public Hover findHover(Position lookupPos) {
    // TODO Auto-generated method stub
    return null;
  }

  public List<CodeLens> findCodeLenses(URI uri) {
    // TODO Auto-generated method stub
    return null;
  }
}
