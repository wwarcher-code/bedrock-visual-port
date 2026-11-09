// WaterSimulation's properties (WaterSimulation.java).
layout(std140) uniform SimData {
    vec4 SimParams0; // G, WaterPatchSize, WaterResolution, WaterWaveDampening
    vec4 SimParams1; // WaterWindVelocity.xy, WaterWindAlignment, WaterSimTime
    vec4 SimParams2; // StockhamFftSubTransformSize, 1 for the horizontal pass, 0 for the vertical
};
