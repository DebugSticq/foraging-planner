# Drei Elementaräxte

Drei eigenständige Low-Poly-Modelle nach deiner Pixel-Art-Vorlage:

- **Frostaxt** — Stahlklinge mit Eiskante, dunkler Griff, Metallhaken.
- **Waldaxt** — Steinklinge, knorriger Astschaft, Moos, Blätter und Ranke.
- **Lavaaxt** — zwei Obsidianklingen, glühende Risse und Goldbeschläge.

Die vier Spalten der Vorlage wurden als Varianten derselben drei Waffen
interpretiert. Jede Waffe ist einmal enthalten. Tiefe und Rückseiten sind
aus der einzigen sichtbaren Bildansicht gestalterisch ergänzt.

## Dateien verwenden

**GLB ist das bevorzugte Format.** Es enthält Geometrie, Normalen und
PBR-Materialien in einer Datei. Importiere die jeweilige `.glb` in Blender
oder eine Game Engine mit glTF-Unterstützung.

Die zusätzlichen `.obj`-Dateien brauchen die gleichnamige `.mtl` im selben
Ordner. OBJ überträgt die Grundfarben; die Interpretation von Metall und
leuchtenden Materialien hängt vom jeweiligen Importer ab.

Öffne **3D_Vorschau.html** in einem WebGL-fähigen Browser. Die Vorschau
funktioniert offline: Modell links wählen, mit der Maus drehen, mit dem
Mausrad zoomen. Frontansicht, Dreiviertelansicht und Drahtgitter sind vorhanden.

**Modellvorschau.png** zeigt eine direkt aus den Modellflächen gerenderte Ansicht.

## Aufbau

- In GLB und OBJ: Y nach oben, Klingenebene XY, Tiefe entlang Z.
- Maßstab: eine Einheit entspricht einem Meter, Gesamtlänge ca. 1,2 m.
- Ursprung im unteren Griffbereich; in der Engine beliebig skalierbar.
- Getrennte, benannte Bauteile für Griff, Klinge und Details.
- Flache Flächennormalen und Materialfarben, keine externen Texturen nötig.
- Leuchtende Materialien im GLB; ein sichtbarer Lichtschein benötigt Bloom
  beziehungsweise eine passende Render-Einstellung in der Zielsoftware.

Keine UV-Texturen, Animationen, Rig, LOD-Stufen oder Kollisionskörper enthalten.
Die Modelle bestehen aus mehreren überlappenden Teilen für die Verwendung
als Spielobjekte und sind nicht als 3D-Druckdateien vorbereitet.

Die Exporte wurden strukturell und geometrisch geprüft; siehe
**Pruefbericht.txt**. Ein Importtest in Blender oder einer Game Engine wurde
nicht durchgeführt. Eine native `.blend`-Datei ist nicht enthalten.
