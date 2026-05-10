# Loqi Language Support for VS Code

This local extension adds syntax highlighting, bracket matching, indentation, and folding for `.loqi` and `.tpg` files.

## Run in development

1. Open `loqi2/syntax_highlight/vscode` in VS Code.
2. Press `F5` and choose `Extension Development Host`.
3. Open any `.loqi` or `.tpg` file in the new VS Code window.

## Build VSIX

Install `vsce` if it is not installed yet:

```powershell
npm install -g @vscode/vsce
```

Then build the extension package from this directory:

```powershell
cd .\loqi2\syntax_highlight\vscode
vsce package --allow-missing-repository
```

This creates a file like:

```text
loqi-syntax-2.1.0.vsix
```

## Install VSIX by drag and drop

1. Open VS Code.
2. Open the Extensions view with `Ctrl+Shift+X`.
3. Drag the generated `.vsix` file into the Extensions view.
4. Confirm installation when VS Code asks.
5. Open or reload a `.loqi` or `.tpg` file.
