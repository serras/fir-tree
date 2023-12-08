# FIR Tree

> Visualize Kotlin's Frontend Intermediate Representation (FIR) right in your IDE

### Available at [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/23270-fir-tree)

This plug-in helps you to understand the way the K2 Kotlin compiler represents the code internally.

- Adjustable resolution phase (the last one, `BODY_RESOLVE`, is the default),
- Click on a node in the tree to select it in the editor,
- Non-visible nodes are shown in italics and slightly grayed out.

Here's the outcome for this piece of code, fully resolved.

```kotlin
package function.equality

import kotlinx.coroutines.*

fun increment(x: Int) = x + 1
```

![FIR tree of the code above](images/example.png)