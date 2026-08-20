function G = polar_generator_matrix(N)
%POLAR_GENERATOR_MATRIX Construct G_N = F^(tensor n) over GF(2).

F = [1, 0; 1, 1];
G = 1;
for index = 1:log2(N)
    G = kron(G, F);
end
G = mod(G, 2);
end
