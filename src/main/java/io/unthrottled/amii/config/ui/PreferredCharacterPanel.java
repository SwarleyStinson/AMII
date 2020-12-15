package io.unthrottled.amii.config.ui;

import com.intellij.ui.GuiUtils;
import io.unthrottled.amii.assets.CharacterEntity;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;

public final class PreferredCharacterPanel {
  private final PreferredCharacterTree myPreferredCharacterTree = new PreferredCharacterTree();
  private JPanel myPanel;
  private JPanel myTreePanel;

  public PreferredCharacterPanel() {
    myTreePanel.setLayout(new BorderLayout());
    myTreePanel.add(myPreferredCharacterTree.getComponent(), BorderLayout.CENTER);

    GuiUtils.replaceJSplitPaneWithIDEASplitter(myPanel);
  }

  public void reset() {
    myPreferredCharacterTree.reset();
  }

  public List<CharacterEntity> getSelected() {
    return myPreferredCharacterTree.getSelected();
  }

  public JPanel getComponent() {
    return myPanel;
  }

  public boolean isModified() {
    return myPreferredCharacterTree.isModified();
  }

  public void dispose() {
    myPreferredCharacterTree.dispose();
  }

  public Runnable showOption(final String option) {
    return () -> {
      myPreferredCharacterTree.filter(myPreferredCharacterTree.filterModel(option, true));
      myPreferredCharacterTree.setFilter(option);
    };
  }
}
