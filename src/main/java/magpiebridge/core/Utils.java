package magpiebridge.core;

import com.ibm.wala.cast.tree.CAstSourcePositionMap;
import com.ibm.wala.cast.tree.impl.AbstractSourcePosition;
import org.apache.commons.io.input.TeeInputStream;
import org.apache.commons.io.output.TeeOutputStream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;

public final class Utils {
  private Utils() {
  }

  /** Wraps an {@link InputStream} so that it writes any read data to a temporary file. */
  static InputStream logStream(InputStream is, String logFileName) {
    File log;
    try {
      log = File.createTempFile(logFileName, ".txt");
      return new TeeInputStream(is, new FileOutputStream(log));
    } catch (IOException e) {
      return is;
    }
  }

  /** Wraps an {@link OutputStream} so that it additionally writes to a temporary file. */
  static OutputStream logStream(OutputStream os, String logFileName) {
    File log;
    try {
      log = File.createTempFile(logFileName, ".txt");
      return new TeeOutputStream(os, new FileOutputStream(log));
    } catch (IOException e) {
      return os;
    }
  }

  /** Converts a WALA {@link CAstSourcePositionMap.Position} to an LSP {@link Location}. */
  static Location getLocationFrom(CAstSourcePositionMap.Position pos) {
    Location codeLocation = new Location();
    try {
      codeLocation.setUri(pos.getURL().toURI().toString());
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }
    Range codeRange = new Range();
    if (pos.getFirstCol() < 0) {
      codeRange.setStart(getPositionFrom(pos.getFirstLine(), 0));// imprecise
    } else {
      codeRange.setStart(getPositionFrom(pos.getFirstLine(), pos.getFirstCol()));
    }
    if (pos.getLastLine() < 0) {
      codeRange.setEnd(getPositionFrom(pos.getFirstLine() + 1, 0));// imprecise
    } else {
      codeRange.setEnd(getPositionFrom(pos.getLastLine(), pos.getLastCol()));
    }
    codeLocation.setRange(codeRange);
    return codeLocation;
  }

  /** Creates an LSP {@link Position} from given line and column. */
  static Position getPositionFrom(int line, int column) {
    Position codeStart = new Position();
    codeStart.setLine(line - 1);
    codeStart.setCharacter(column);
    return codeStart;
  }

  /** Converts an LSP {@link Position} (plus URL) to a WALA {@link CAstSourcePositionMap.Position}. */
  protected static CAstSourcePositionMap.Position lookupPos(Position pos, URL url) {
    return new AbstractSourcePosition() {

      @Override
      public int getFirstLine() {
        // LSP is 0-based, but parsers mostly 1-based
        return pos.getLine() + 1;
      }

      @Override
      public int getLastLine() {
        // LSP is 0-based, but parsers mostly 1-based
        return pos.getLine() + 1;
      }

      @Override
      public int getFirstCol() {
        return pos.getCharacter();
      }

      @Override
      public int getLastCol() {
        return pos.getCharacter();
      }

      @Override
      public int getFirstOffset() {
        return -1;
      }

      @Override
      public int getLastOffset() {
        return -1;
      }

      @Override
      public URL getURL() {
        return url;
      }

      @Override
      public Reader getReader() throws IOException {
        return new InputStreamReader(url.openConnection().getInputStream());
      }

      @Override
      public String toString() {
        return url + ":" + getFirstLine() + "," + getFirstCol();
      }
    };
  }
}
